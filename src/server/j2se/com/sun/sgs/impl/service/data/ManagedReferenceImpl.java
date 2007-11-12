/*
 * Copyright 2007 Sun Microsystems, Inc.
 *
 * This file is part of Project Darkstar Server.
 *
 * Project Darkstar Server is free software: you can redistribute it
 * and/or modify it under the terms of the GNU General Public License
 * version 2 as published by the Free Software Foundation and
 * distributed hereunder to you.
 *
 * Project Darkstar Server is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.sun.sgs.impl.service.data;

import com.sun.sgs.app.ManagedObject;
import com.sun.sgs.app.ManagedReference;
import com.sun.sgs.app.ObjectIOException;
import com.sun.sgs.app.ObjectNotFoundException;
import com.sun.sgs.app.TransactionNotActiveException;
import com.sun.sgs.impl.sharedutil.LoggerWrapper;
import com.sun.sgs.impl.sharedutil.Objects;
import java.io.ObjectStreamException;
import java.io.Serializable;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Provides an implementation of ManagedReference.  Instances of this class are
 * associated with a single transaction.  Within a transaction, instances are
 * canonicalized: only a single instance appears for a given object ID or
 * object.
 */
final class ManagedReferenceImpl implements ManagedReference, Serializable {

    /** The version of the serialized form. */
    private static final long serialVersionUID = 1;

    /** The logger for this class. */
    private static final LoggerWrapper logger =
	new LoggerWrapper(
	    Logger.getLogger(ManagedReferenceImpl.class.getName()));

    /**
     * The logger for messages about managed objects that are modified but for
     * which markForUpdate was not called.
     */
    private static final LoggerWrapper debugDetectLogger =
	new LoggerWrapper(
	    Logger.getLogger(
		DataServiceImpl.class.getName() + ".detect.modifications"));

    /**
     * The possible states of a reference.
     *
     * Here's a table relating state values to the values of the object and
     * unmodifiedBytes fields:
     *
     *   State		  object    unmodifiedBytes
     *   NEW		  non-null  null
     *   EMPTY		  null      null
     *   NOT_MODIFIED	  non-null  null
     *   MAYBE_MODIFIED   non-null  non-null
     *   MODIFIED	  non-null  null
     *	 FLUSHED	  null      null
     *	 REMOVED_EMPTY	  null      null
     *	 REMOVED_FETCHED  non-null  null
     */
    private static enum State {

	/** A object created in this transaction. */
	NEW,

	/**
	 * A reference to an existing object that has not been dereferenced
	 * yet.
	 */
	EMPTY,

	/**
	 * An object that has been read and will be marked explicitly when
	 * modified.
	 */
	NOT_MODIFIED,

	/**
	 * An object that has been read and will be checked for modification at
	 * commit.
	 */
	MAYBE_MODIFIED,

	/** An object that has been explicitly marked modified. */
	MODIFIED,

	/**
	 * An object whose contents have been flushed to the database during
	 * transaction preparation.
	 */
	FLUSHED,

	/** An object that has been removed without being dereferenced */
	REMOVED_EMPTY,

	/** An object that has been removed after being dereferenced. */
	REMOVED_FETCHED
    }

    /**
     * Information related to the transaction in which this reference was
     * created.  This field is logically final, but is not declared final so
     * that it can be set during deserialization.
     */
    private transient Context context;

    /** The value returned by getId, or null. */
    private transient BigInteger id;

    /**
     * The object ID.
     *
     * @serial
     */
    final long oid;

    /**
     * The associated object or null.  Note that managed references cannot
     * refer to null, so this field will only be null if the object has not
     * been fetched yet.
     */
    private transient ManagedObject object;

    /**
     * The serialized form of the object before it was modified, or null.  Note
     * that this value could be different from the bytes used to deserialize
     * the object if the serialized form of the object is not stable.
     * Unfortunately, the built-in collection types have non-stable serialized
     * forms.
     */
    private transient byte[] unmodifiedBytes;

    /** The current state. */
    private transient State state;

    /* -- Getting instances -- */

    /**
     * Returns the reference associated with a context and object, or null if
     * the reference is not found.  Throws ObjectNotFoundException if the
     * object has been removed.
     */
    static ManagedReferenceImpl findReference(
	Context context, ManagedObject object)
    {
	assert object != null : "Object is null";
	ManagedReferenceImpl ref = context.refs.find(object);
	if (ref != null && ref.isRemoved()) {
	    throw new ObjectNotFoundException("Object has been removed");
	}
	return ref;
     }

    /**
     * Returns the reference associated with a context and object, creating a
     * NEW reference if none is found.
     */
    static ManagedReferenceImpl getReference(
	Context context, ManagedObject object)
    {
	assert object != null : "Object is null";
	ManagedReferenceImpl ref = context.refs.find(object);
	if (ref == null) {
	    ref = new ManagedReferenceImpl(context, object);
	    context.refs.add(ref);
	} else if (ref.isRemoved()) {
	    throw new ObjectNotFoundException("Object has been removed");
	}
	if (logger.isLoggable(Level.FINEST)) {
	    logger.log(Level.FINEST,
		       "getReference tid:{0,number,#}, object:{1}" +
		       " returns oid:{2,number,#}",
		       context.getTxnId(), Objects.fastToString(object),
		       ref.getId());
	}
	return ref;
    }

    /**
     * Returns the reference associated with a context and object ID, creating
     * an EMPTY reference if none is found.
     */
    static ManagedReferenceImpl getReference(Context context, long oid) {
	ManagedReferenceImpl ref = context.refs.find(oid);
	if (ref == null) {
	    ref = new ManagedReferenceImpl(context, oid);
	    context.refs.add(ref);
	} else if (ref.isRemoved()) {
	    throw new ObjectNotFoundException("Object has been removed");
	}
	if (logger.isLoggable(Level.FINEST)) {
	    logger.log(
		Level.FINEST,
		"getReference tid:{0,number,#}, oid:{1,number,#} returns",
		context.getTxnId(), oid);
	}
	return ref;
    }

    /** Creates a NEW reference to an object. */
    private ManagedReferenceImpl(Context context, ManagedObject object) {
	this.context = context;
	oid = context.store.createObject(context.txn);
	this.object = object;
	state = State.NEW;
	validate();
    }

    /** Creates an EMPTY reference to an object ID. */
    private ManagedReferenceImpl(Context context, long oid) {
	this.context = context;
	this.oid = oid;
	state = State.EMPTY;
	validate();
    }

    /* -- Methods for DataService -- */

    @SuppressWarnings("fallthrough")
    void removeObject() {
	switch (state) {
	case EMPTY:
	    context.store.removeObject(context.txn, oid);
	    state = State.REMOVED_EMPTY;
	    break;
	case MAYBE_MODIFIED:
	    /* Call store before modifying fields, in case the call fails */
	    context.store.removeObject(context.txn, oid);
	    unmodifiedBytes = null;
	    state = State.REMOVED_FETCHED;
	    break;
	case NOT_MODIFIED:
	case MODIFIED:
	    context.store.removeObject(context.txn, oid);
	    /* Fall through */
	case NEW:
	    state = State.REMOVED_FETCHED;
	    break;
	case FLUSHED:
	    throw new TransactionNotActiveException(
		"No transaction is in progress");
	case REMOVED_EMPTY:
	case REMOVED_FETCHED:
	    throw new ObjectNotFoundException("The object is not found");
	default:
	    throw new AssertionError();
	}
    }

    @SuppressWarnings("fallthrough")
    void markForUpdate() {
	switch (state) {
	case EMPTY:
	    /*
	     * Presumably this object is being marked for update because it
	     * will be modified, so fetch the object now.
	     */
	    object = deserialize(
		context.store.getObject(context.txn, oid, true));
	    context.refs.registerObject(this);
	    state = State.MODIFIED;
	    break;
	case MAYBE_MODIFIED:
	    context.store.markForUpdate(context.txn, oid);
	    unmodifiedBytes = null;
	    state = State.MODIFIED;
	    break;
	case NOT_MODIFIED:
	    context.store.markForUpdate(context.txn, oid);
	    state = State.MODIFIED;
	    break;
	case MODIFIED:
	case NEW:
	    break;
	case FLUSHED:
	    throw new TransactionNotActiveException(
		"No transaction is in progress");
	case REMOVED_EMPTY:
	case REMOVED_FETCHED:
	    throw new ObjectNotFoundException("The object is not found");
	default:
	    throw new AssertionError();
	}
    }

    /* -- Implement ManagedReference -- */

    /** {@inheritDoc} */
    public <T> T get(Class<T> type) {
	return get(type, true);
    }

    /**
     * Like get, but with optional checking of the context.  Suppress the check
     * if the reference was just obtained from the context.
     */
    @SuppressWarnings("fallthrough")
    <T> T get(Class<T> type, boolean checkContext) {
	if (type == null) {
	    throw new NullPointerException(
		"The type argument must not be null");
	}
	try {
	    if (checkContext) {
		DataServiceImpl.checkContext(context);
	    }
	    switch (state) {
	    case EMPTY:
		ManagedObject tempObject = deserialize(
		    context.store.getObject(context.txn, oid, false));
		if (context.detectModifications) {
		    unmodifiedBytes = SerialUtil.serialize(
			tempObject, context.classSerial);
		    state = State.MAYBE_MODIFIED;
		} else {
		    state = State.NOT_MODIFIED;
		}
		/* Do after creating unmodified bytes, in case that fails */
		object = tempObject;
		context.refs.registerObject(this);
		break;
	    case NEW:
	    case NOT_MODIFIED:
	    case MAYBE_MODIFIED:
	    case MODIFIED:
		break;
	    case FLUSHED:
		throw new TransactionNotActiveException(
		    "No transaction is in progress");
	    case REMOVED_EMPTY:
	    case REMOVED_FETCHED:
		throw new ObjectNotFoundException("The object is not found");
	    default:
		throw new AssertionError();
	    }
	    if (logger.isLoggable(Level.FINEST)) {
		logger.log(
		    Level.FINEST,
		    "get tid:{0,number,#}, oid:{1,number,#} returns {2}",
		    context.getTxnId(), oid, Objects.fastToString(object));
	    }
	    return type.cast(object);
	} catch (TransactionNotActiveException e) {
	    throw new TransactionNotActiveException(
		"Attempt to obtain the object associated with a managed " +
		"reference that was created in another transaction",
		e);
	} catch (RuntimeException e) {
	    logger.logThrow(Level.FINEST, e,
			    "get tid:{0,number,#}, oid:{1,number,#} throws",
			    context.getTxnId(), oid);
	    throw e;
	}
    }

    /** {@inheritDoc} */
    @SuppressWarnings("fallthrough")
    public <T> T getForUpdate(Class<T> type) {
	if (type == null) {
	    throw new NullPointerException(
		"The type argument must not be null");
	}
	RuntimeException exception;
	try {
	    DataServiceImpl.checkContext(context);
	    switch (state) {
	    case EMPTY:
		object = deserialize(
		    context.store.getObject(context.txn, oid, true));
		context.refs.registerObject(this);
		state = State.MODIFIED;
		break;
	    case MAYBE_MODIFIED:
		context.store.markForUpdate(context.txn, oid);
		unmodifiedBytes = null;
		state = State.MODIFIED;
		break;
	    case NOT_MODIFIED:
		context.store.markForUpdate(context.txn, oid);
		state = State.MODIFIED;
		break;
	    case FLUSHED:
		throw new TransactionNotActiveException(
		    "No transaction is in progress");
	    case NEW:
	    case MODIFIED:
		break;
	    case REMOVED_EMPTY:
	    case REMOVED_FETCHED:
		throw new ObjectNotFoundException("The object is not found");
	    default:
		throw new AssertionError();
	    }
	    if (logger.isLoggable(Level.FINEST)) {
		logger.log(Level.FINEST,
			   "getForUpdate tid:{0,number,#}, oid:{1,number,#}" +
			   " returns {2}",
			   context.getTxnId(), oid,
			   Objects.fastToString(object));
	    }
	    return type.cast(object);
	} catch (TransactionNotActiveException e) {
	    exception = new TransactionNotActiveException(
		"Attempt to obtain the object associated with a managed " +
		"reference that was created in another transaction",
		e);
	} catch (RuntimeException e) {
	    exception = e;
	}
	logger.logThrow(
	    Level.FINEST, exception,
	    "getForUpdate tid:{0,number,#}, oid:{1,number,#} throws",
	    context.getTxnId(), oid);
	throw exception;
    }

    /** {@inheritDoc} */
    public BigInteger getId() {
	if (id == null) {
	    id = BigInteger.valueOf(oid);
	}
	return id;
    }

    /** {@inheritDoc} */
    public boolean equals(Object object) {
	if (object == this) {
	    return true;
	} else if (object instanceof ManagedReferenceImpl) {
	    /*
	     * This implementation depends on the fact that references are
	     * associated with the current data service, which is true since
	     * the data service is obtained from the current context rather
	     * than being represented explicitly within the reference.  If it
	     * were possible to compare references associated with different
	     * data services, that would produce false equality for references
	     * to objects in the different data services that happened to have
	     * the same object IDs.  -tjb@sun.com (11/09/2007)
	     */
	    return oid == (((ManagedReferenceImpl) object).oid);
	} else {
	    return false;
	}
    }

    /** {@inheritDoc} */
    public int hashCode() {
	/*
	 * Follow the suggestions in Effective Java to XOR the upper and lower
	 * 32 bits of a long field, and add a non-zero constant.
	 */
	return (int) (oid ^ (oid >>> 32)) + 6883;
    }

    /* -- Implement Serializable -- */

    /** Replaces this instance with a canonical instance. */
    private Object readResolve() throws ObjectStreamException {
	context = DataServiceImpl.getContextNoJoin();
	state = State.EMPTY;
	validate();
	ManagedReferenceImpl ref = context.refs.find(oid);
	if (ref == null) {
	    context.refs.add(this);
	    return this;
	} else {
	    return ref;
	}
    }

    /* -- Object methods -- */

    public String toString() {
	return "ManagedReferenceImpl[oid:" + oid + ", state:" + state + "]";
    }

    /* -- Other methods -- */

    /**
     * Checks the consistency of the managed references table, throwing an
     * exception if a problem is found.
     */
    static void checkAllState(Context context) {
	logger.log(Level.FINE, "Checking state");
	try {
	    context.refs.checkAllState();
	} catch (AssertionError e) {
	    logger.logThrow(Level.SEVERE, e, "State check failed");
	    throw e;
	}
    }

    /**
     * Checks the fields of this object to make sure they have valid values,
     * throwing an assertion error if a problem is found.
     */
    @SuppressWarnings("fallthrough")
    void checkState() {
	switch (state) {
	case NEW:
	    if (object == null) {
		throw new AssertionError("NEW with no object");
	    } else if (unmodifiedBytes != null) {
		throw new AssertionError("NEW with unmodifiedBytes");
	    }
	    break;
	case EMPTY:
	case FLUSHED:
	case REMOVED_EMPTY:
	    if (object != null) {
		throw new AssertionError(state + " with object");
	    } else if (unmodifiedBytes != null) {
		throw new AssertionError(state + " with unmodifiedBytes");
	    }
	    break;
	case NOT_MODIFIED:
	case MODIFIED:
	case REMOVED_FETCHED:
	    if (object == null) {
		throw new AssertionError(state + " with no object");
	    } else if (unmodifiedBytes != null) {
		throw new AssertionError(state + " with unmodifiedBytes");
	    }
	    break;
	case MAYBE_MODIFIED:
	    if (object == null) {
		throw new AssertionError(
		    "MAYBE_MODIFIED with no object");
	    } else if (unmodifiedBytes == null) {
		throw new AssertionError(
		    "MAYBE_MODIFIED with no unmodifiedBytes");
	    }
	    break;
	default:
	    throw new AssertionError();
	}
    }

    /** Saves all object modifications to the data store. */
    static void flushAll(Context context) {
	FlushInfo info = context.refs.flushModifiedObjects();
	if (info != null) {
	    context.store.setObjects(
		context.txn, info.getOids(), info.getDataArray());
	}
    }

    /**
     * Returns the next object ID, or -1 if there are no more objects.  Does
     * not return IDs for removed objects.  Specifying -1 requests the first
     * ID.
     */
    static long nextObjectId(Context context, long oid) {
	long lastFound = oid;
	while (true) {
	    long result = context.store.nextObjectId(context.txn, lastFound);
	    if (result == -1) {
		break;
	    }
	    lastFound = result;
	    ManagedReferenceImpl ref = context.refs.find(lastFound);
	    if (ref == null || !ref.isRemoved()) {
		return result;
	    }
	}
	/*
	 * Check for newly created objects that don't appear in the data store
	 * but are recorded in the reference table.
	 */
	return context.refs.nextNewObjectId(lastFound);
    }

    /**
     * Returns any modifications that need to be stored to the data store, or
     * null if there are none, and changes the state to FLUSHED.
     */
    @SuppressWarnings("fallthrough")
    byte[] flush() {
	byte[] result = null;
	switch (state) {
	case EMPTY:
	case REMOVED_EMPTY:
	    break;
	case NEW:
	case MODIFIED:
	    result = SerialUtil.serialize(object, context.classSerial);
	    context.refs.unregisterObject(object);
	    break;
	case MAYBE_MODIFIED:
	    byte[] modified =
		SerialUtil.serialize(object, context.classSerial);
	    if (!Arrays.equals(modified, unmodifiedBytes)) {
		result = modified;
		if (debugDetectLogger.isLoggable(Level.FINEST)) {
		    debugDetectLogger.log(
			Level.FINEST,
			"Modified object was not marked for update: {0}",
			Objects.fastToString(object));
		}
	    }
	    /* Fall through */
	case NOT_MODIFIED:
	case REMOVED_FETCHED:
	    context.refs.unregisterObject(object);
	    break;
	case FLUSHED:
	    throw new IllegalStateException("Object already flushed");
	default:
	    throw new AssertionError();
	}
	object = null;
	unmodifiedBytes = null;
	state = State.FLUSHED;
	return result;
    }

    /**
     * Checks if the object has been marked removed.  This method will return
     * false if the object was not removed in this transaction.
     */
    boolean isRemoved() {
	return state == State.REMOVED_EMPTY || state == State.REMOVED_FETCHED;
    }

    /**
     * Checks if the object has been created in the current transaction and not
     * removed.
     */
    boolean isNew() {
	return state == State.NEW;
    }

    /** Returns the object currently associated with this reference or null. */
    ManagedObject getObject() {
	return object;
    }

    /** Validates the values of the context and oid fields. */
    private void validate() {
	if (context == null) {
	    throw new NullPointerException("The context must not be null");
	}
	if (oid < 0) {
	    throw new IllegalArgumentException("The oid must not be negative");
	}
    }

    /**
     * Returns the managed object associated with serialized data.  Checks that
     * the return value is not null.
     */
    private ManagedObject deserialize(byte[] data) {
	Object obj =  SerialUtil.deserialize(data, context.classSerial);
	if (obj == null) {
	    throw new ObjectIOException(
		"Managed object must not deserialize to null", false);
	} else if (!(obj instanceof ManagedObject)) {
	    throw new ObjectIOException(
		"Deserialized object must implement ManagedObject", false);
	}
	return (ManagedObject) obj;
    }
}
