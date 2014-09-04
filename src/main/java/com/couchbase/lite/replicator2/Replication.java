package com.couchbase.lite.replicator2;

import com.couchbase.lite.Database;
import com.couchbase.lite.Manager;
import com.couchbase.lite.auth.Authenticator;
import com.couchbase.lite.internal.InterfaceAudience;
import com.couchbase.lite.support.CouchbaseLiteHttpClientFactory;
import com.couchbase.lite.support.HttpClientFactory;
import com.couchbase.lite.support.PersistentCookieStore;
import com.couchbase.lite.util.Log;
import com.couchbase.lite.util.TextUtils;
import com.github.oxo42.stateless4j.transitions.Transition;

import org.apache.http.cookie.Cookie;
import org.apache.http.impl.cookie.BasicClientCookie2;

import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * The external facade for the Replication API
 */
public class Replication implements ReplicationInternal.ChangeListener {

    public enum Direction { PULL, PUSH };
    public enum Lifecycle { ONESHOT, CONTINUOUS };

    /**
     * @exclude
     */
    public static final String REPLICATOR_DATABASE_NAME = "_replicator";

    protected Database db;
    protected URL remote;
    protected HttpClientFactory clientFactory;
    protected ScheduledExecutorService workExecutor;
    protected ReplicationInternal replicationInternal;
    protected Lifecycle lifecycle;
    protected List<ChangeListener> changeListeners;
    protected Throwable lastError;


    /**
     * Constructor
     * @exclude
     */
    @InterfaceAudience.Private
    public Replication(Database db, URL remote, Direction direction) {
        this(
                db,
                remote,
                direction,
                db.getManager().getDefaultHttpClientFactory(),
                Executors.newSingleThreadScheduledExecutor()
        );

    }

    /**
     * Constructor
     * @exclude
     */
    @InterfaceAudience.Private
    public Replication(Database db, URL remote, Direction direction, HttpClientFactory clientFactory, ScheduledExecutorService workExecutor) {

        this.db = db;
        this.remote = remote;
        this.workExecutor = workExecutor;
        this.changeListeners = new CopyOnWriteArrayList<ChangeListener>();
        this.lifecycle = Lifecycle.ONESHOT;

        setClientFactory(clientFactory);

        switch (direction) {
            case PULL:
                replicationInternal = new PullerInternal(
                        this.db,
                        this.remote,
                        this.clientFactory,
                        this.workExecutor,
                        this.lifecycle,
                        this
                );
                break;
            case PUSH:
                replicationInternal = new PusherInternal(
                        this.db,
                        this.remote,
                        this.clientFactory,
                        this.workExecutor,
                        this.lifecycle,
                        this
                );
                break;
            default:
                throw new RuntimeException(String.format("Unknown direction: %s", direction));
        }

        replicationInternal.addChangeListener(this);

    }

    /**
     * Starts the replication, asynchronously.
     */
    @InterfaceAudience.Public
    public void start() {
        replicationInternal.triggerStart();
    }

    /**
     * Tell the replication to go offline, asynchronously.
     */
    @InterfaceAudience.Public
    public void goOffline() {
        replicationInternal.triggerGoOffline();
    }

    /**
     * Tell the replication to go online, asynchronously.
     */
    @InterfaceAudience.Public
    public void goOnline() {
        replicationInternal.triggerGoOnline();
    }

    /**
     * True while the replication is running, False if it's stopped.
     * Note that a continuous replication never actually stops; it only goes idle waiting for new
     * data to appear.
     */
    @InterfaceAudience.Public
    public boolean isRunning() {
        if (replicationInternal == null) {
            return false;
        }
        return replicationInternal.stateMachine.isInState(ReplicationState.RUNNING);
    }

    /**
     * Stops the replication, asynchronously.
     */
    @InterfaceAudience.Public
    public void stop() {
        if (replicationInternal != null) {
            replicationInternal.triggerStop();
        }
    }

    /**
     * Is this replication continous?
     */
    @InterfaceAudience.Public
    public boolean isContinuous() {
        return lifecycle == Lifecycle.CONTINUOUS;
    }

    /**
     * Set whether this replication is continous
     */
    @InterfaceAudience.Public
    public void setContinuous(boolean isContinous) {
        if (isContinous) {
            this.lifecycle = Lifecycle.CONTINUOUS;
            replicationInternal.setLifecycle(Lifecycle.CONTINUOUS);
        } else {
            this.lifecycle = Lifecycle.ONESHOT;
            replicationInternal.setLifecycle(Lifecycle.ONESHOT);

        }

    }

    /**
     * Set the Authenticator used for authenticating with the Sync Gateway
     */
    @InterfaceAudience.Public
    public void setAuthenticator(Authenticator authenticator) {
        replicationInternal.setAuthenticator(authenticator);
    }

    /**
     * Get the Authenticator used for authenticating with the Sync Gateway
     */
    @InterfaceAudience.Public
    public Authenticator getAuthenticator() {
        return replicationInternal.getAuthenticator();
    }

    /**
     * Should the target database be created if it doesn't already exist? (Defaults to NO).
     */
    @InterfaceAudience.Public
    public boolean shouldCreateTarget() {
        return replicationInternal.shouldCreateTarget();
    }

    /**
     * Set whether the target database be created if it doesn't already exist?
     */
    @InterfaceAudience.Public
    public void setCreateTarget(boolean createTarget) {
        replicationInternal.setCreateTarget(createTarget);
    };

    /**
     * Adds a change delegate that will be called whenever the Replication changes.
     */
    @InterfaceAudience.Public
    public void addChangeListener(ChangeListener changeListener) {
        changeListeners.add(changeListener);
    }

    /**
     * Removes the specified delegate as a listener for the Replication change event.
     */
    @InterfaceAudience.Public
    public void removeChangeListener(ChangeListener changeListener) {
        changeListeners.remove(changeListener);
    }

    /**
     * This is called back for changes from the ReplicationInternal.
     * Simply propagate the events back to all listeners.
     */
    @Override
    public void changed(ChangeEvent event) {
        for (ChangeListener changeListener : changeListeners) {
            try {
                changeListener.changed(event);
            } catch (Exception e) {
                Log.e(Log.TAG_SYNC, "Exception calling changeListener.changed", e);
            }
        }
    }

    /**
     * The error status of the replication, or null if there have not been any errors since
     * it started.
     */
    @InterfaceAudience.Public
    public Throwable getLastError() {
        return lastError;
    }

    /**
     * The number of completed changes processed, if the task is active, else 0 (observable).
     */
    @InterfaceAudience.Public
    public int getCompletedChangesCount() {
        return replicationInternal.getCompletedChangesCount().get();
    }

    /**
     * The total number of changes to be processed, if the task is active, else 0 (observable).
     */
    @InterfaceAudience.Public
    public int getChangesCount() {
        return replicationInternal.getChangesCount().get();
    }

    /**
     * Update the lastError
     */
    /* package */ void setLastError(Throwable lastError) {
        this.lastError = lastError;
    }

    /* package */ String remoteCheckpointDocID() {
        return replicationInternal.remoteCheckpointDocID();
    }

    /**
     * A delegate that can be used to listen for Replication changes.
     */
    @InterfaceAudience.Public
    public static interface ChangeListener {
        public void changed(ChangeEvent event);
    }

    /**
     * The type of event raised by a Replication when any of the following
     * properties change: mode, running, error, completed, total.
     */
    @InterfaceAudience.Public
    public static class ChangeEvent {

        private Replication source;
        private ReplicationStateTransition transition;
        private int changeCount;
        private int completedChangeCount;
        private Throwable error;

        /* package */ ChangeEvent(ReplicationInternal replInternal) {
            this.source = replInternal.parentReplication;
            this.changeCount = replInternal.getChangesCount().get();
            this.completedChangeCount =replInternal.getCompletedChangesCount().get();
        }

        public Replication getSource() {
            return source;
        }

        public ReplicationStateTransition getTransition() {
            return transition;
        }

        public void setTransition(ReplicationStateTransition transition) {
            this.transition = transition;
        }

        public int getChangeCount() {
            return changeCount;
        }

        public int getCompletedChangeCount() {
            return completedChangeCount;
        }

        /**
         * Get the error that triggered this callback, if any.  There also might
         * be a non-null error saved by the replicator from something that previously
         * happened, which you can get by calling getSource().getLastError().
         */
        public Throwable getError() {
            return error;
        }

        /* package */ void setError(Throwable error) {
            this.error = error;
        }
    }

    /**
     * Set the HTTP client factory if one was passed in, or use the default
     * set in the manager if available.
     * @param clientFactory
     */
    @InterfaceAudience.Private
    protected void setClientFactory(HttpClientFactory clientFactory) {
        Manager manager = null;
        if (this.db != null) {
            manager = this.db.getManager();
        }
        HttpClientFactory managerClientFactory = null;
        if (manager != null) {
            managerClientFactory = manager.getDefaultHttpClientFactory();
        }
        if (clientFactory != null) {
            this.clientFactory = clientFactory;
        } else {
            if (managerClientFactory != null) {
                this.clientFactory = managerClientFactory;
            } else {
                PersistentCookieStore cookieStore = db.getPersistentCookieStore();
                this.clientFactory = new CouchbaseLiteHttpClientFactory(cookieStore);
            }
        }
    }

    @InterfaceAudience.Private
    /* package */ boolean serverIsSyncGatewayVersion(String minVersion) {
        return replicationInternal.serverIsSyncGatewayVersion(minVersion);
    }

    @InterfaceAudience.Private
    /* package */ void setServerType(String serverType) {
        replicationInternal.setServerType(serverType);
    }

    /**
     * Set the filter to be used by this replication
     */
    @InterfaceAudience.Public
    public void setFilter(String filterName) {
        replicationInternal.setFilter(filterName);
    }

    /**
     * Sets the documents to specify as part of the replication.
     */
    @InterfaceAudience.Public
    public void setDocIds(List<String> docIds) {
        replicationInternal.setDocIds(docIds);
    }

    /**
     * Set parameters to pass to the filter function.
     */
    public void setFilterParams(Map<String, Object> filterParams) {
        replicationInternal.setFilterParams(filterParams);
    }

    /**
     * Sets an HTTP cookie for the Replication.
     *
     * @param name The name of the cookie.
     * @param value The value of the cookie.
     * @param path The path attribute of the cookie.  If null or empty, will use remote.getPath()
     * @param maxAge The maxAge, in milliseconds, that this cookie should be valid for.
     * @param secure Whether the cookie should only be sent using a secure protocol (e.g. HTTPS).
     * @param httpOnly (ignored) Whether the cookie should only be used when transmitting HTTP, or HTTPS, requests thus restricting access from other, non-HTTP APIs.
     */
    @InterfaceAudience.Public
    public void setCookie(String name, String value, String path, long maxAge, boolean secure, boolean httpOnly) {
        replicationInternal.setCookie(name, value, path, maxAge, secure, httpOnly);
    }

    /**
     * Sets an HTTP cookie for the Replication.
     *
     * @param name The name of the cookie.
     * @param value The value of the cookie.
     * @param path The path attribute of the cookie.  If null or empty, will use remote.getPath()
     * @param expirationDate The expiration date of the cookie.
     * @param secure Whether the cookie should only be sent using a secure protocol (e.g. HTTPS).
     * @param httpOnly (ignored) Whether the cookie should only be used when transmitting HTTP, or HTTPS, requests thus restricting access from other, non-HTTP APIs.
     */
    @InterfaceAudience.Public
    public void setCookie(String name, String value, String path, Date expirationDate, boolean secure, boolean httpOnly) {
        replicationInternal.setCookie(name, value, path, expirationDate, secure, httpOnly);

    }

    /**
     * Deletes an HTTP cookie for the Replication.
     *
     * @param name The name of the cookie.
     */
    @InterfaceAudience.Public
    public void deleteCookie(String name) {
        replicationInternal.deleteCookie(name);
    }

    @InterfaceAudience.Private
    /* package */ HttpClientFactory getClientFactory() {
        return replicationInternal.getClientFactory();
    }

    @InterfaceAudience.Private
    /* package */ String buildRelativeURLString(String relativePath) {

        return replicationInternal.buildRelativeURLString(relativePath);

    }

    /**
     * List of Sync Gateway channel names to filter by; a nil value means no filtering, i.e. all
     * available channels will be synced.  Only valid for pull replications whose source database
     * is on a Couchbase Sync Gateway server.  (This is a convenience that just reads or
     * changes the values of .filter and .query_params.)
     */
    @InterfaceAudience.Public
    public List<String> getChannels() {
        return replicationInternal.getChannels();
    }

    /**
     * Set the list of Sync Gateway channel names
     */
    @InterfaceAudience.Public
    public void setChannels(List<String> channels) {
        replicationInternal.setChannels(channels);
    }

    /**
     * Name of an optional filter function to run on the source server. Only documents for
     * which the function returns true are replicated.
     *
     * For a pull replication, the name looks like "designdocname/filtername".
     * For a push replication, use the name under which you registered the filter with the Database.
     */
    @InterfaceAudience.Public
    public String getFilter() {
        return replicationInternal.getFilter();
    }

    /**
     * Parameters to pass to the filter function.  Should map strings to strings.
     */
    @InterfaceAudience.Public
    public Map<String, Object> getFilterParams() {
        return replicationInternal.getFilterParams();
    }

    /**
     * Set Extra HTTP headers to be sent in all requests to the remote server.
     */
    @InterfaceAudience.Public
    public void setHeaders(Map<String, Object> requestHeadersParam) {
        replicationInternal.setHeaders(requestHeadersParam);
    }

    /**
     * Extra HTTP headers to send in all requests to the remote server.
     * Should map strings (header names) to strings.
     */
    @InterfaceAudience.Public
    public Map<String, Object> getHeaders() {
        return replicationInternal.getHeaders();
    }

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public void databaseClosing() {
        replicationInternal.databaseClosing();
    }

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public String getSessionID() {
        return replicationInternal.getSessionID();
    }

    /**
     * Get the local database which is the source or target of this replication
     */
    @InterfaceAudience.Public
    public Database getLocalDatabase() {
        return db;
    }

    /**
     * Get the remote URL which is the source or target of this replication
     */
    @InterfaceAudience.Public
    public URL getRemoteUrl() {
        return remote;
    }

    /**
     * Is this a pull replication?  (Eg, it pulls data from Sync Gateway -> Device running CBL?)
     */
    @InterfaceAudience.Public
    public boolean isPull() {
        return replicationInternal.isPull();
    }

}
