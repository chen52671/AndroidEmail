/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package com.chen.emailcommon.utility;

import android.content.Context;

import com.chen.emailcommon.Logging;
import com.chen.emailcommon.provider.HostAuth;
import com.chen.mail.utils.LogUtils;

import org.apache.http.conn.scheme.PlainSocketFactory;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.scheme.SchemeRegistry;
import org.apache.http.impl.conn.tsccm.ThreadSafeClientConnManager;
import org.apache.http.params.HttpParams;

import java.security.cert.CertificateException;

import javax.net.ssl.KeyManager;

/**
 * A thread-safe client connection manager that manages the use of client certificates from the
 * {@link android.security.KeyChain} for SSL connections.
 */
public class EmailClientConnectionManager extends ThreadSafeClientConnManager {

    private static final int STANDARD_PORT = 80;
    private static final int STANDARD_SSL_PORT = 443;
    private static final boolean LOG_ENABLED = false;

    /**
     * A {@link KeyManager} to track client certificate requests from servers.
     */
    private final SSLUtils.TrackingKeyManager mTrackingKeyManager;

    /**
     * Not publicly instantiable except via {@link #newInstance(HttpParams)}
     */
    private EmailClientConnectionManager(
            HttpParams params, SchemeRegistry registry, SSLUtils.TrackingKeyManager keyManager) {
        super(params, registry);
        mTrackingKeyManager = keyManager;
    }

    public static EmailClientConnectionManager newInstance(Context context, HttpParams params,
            HostAuth hostAuth) {
        SSLUtils.TrackingKeyManager keyManager = new SSLUtils.TrackingKeyManager();
        boolean ssl = hostAuth.shouldUseSsl();
        int port = hostAuth.mPort;

        // Create a registry for our three schemes; http and https will use built-in factories
        SchemeRegistry registry = new SchemeRegistry();
        registry.register(new Scheme("http", PlainSocketFactory.getSocketFactory(),
                ssl ? STANDARD_PORT : port));
        // Register https with the secure factory
        registry.register(new Scheme("https",
                SSLUtils.getHttpSocketFactory(context, hostAuth, keyManager, false),
                ssl ? port : STANDARD_SSL_PORT));
        // Register the httpts scheme with our insecure factory
        registry.register(new Scheme("httpts",
                SSLUtils.getHttpSocketFactory(context, hostAuth, keyManager, true),
                ssl ? port : STANDARD_SSL_PORT));

        return new EmailClientConnectionManager(params, registry, keyManager);
    }

    /**
     * Ensures that a client SSL certificate is known to be used for the specified connection
     * manager.
     * A {@link SchemeRegistry} is used to denote which client certificates to use for a given
     * connection, so clients of this connection manager should use
     * {@link #makeSchemeForClientCert(String, boolean)}.
     */
    public synchronized void registerClientCert(Context context, HostAuth hostAuth)
            throws CertificateException {
        SchemeRegistry registry = getSchemeRegistry();
        String schemeName = makeSchemeForClientCert(hostAuth.mClientCertAlias,
                hostAuth.shouldTrustAllServerCerts());
        Scheme existing = registry.get(schemeName);
        if (existing == null) {
            if (LOG_ENABLED) {
                LogUtils.i(Logging.LOG_TAG, "Registering socket factory for certificate alias ["
                        + hostAuth.mClientCertAlias + "]");
            }
            KeyManager keyManager =
                    SSLUtils.KeyChainKeyManager.fromAlias(context, hostAuth.mClientCertAlias);
            boolean insecure = hostAuth.shouldTrustAllServerCerts();
            SSLSocketFactory ssf =
                    SSLUtils.getHttpSocketFactory(context, hostAuth, keyManager, insecure);
            registry.register(new Scheme(schemeName, ssf, hostAuth.mPort));
        }
    }

    /**
     * Unregisters a custom connection type that uses a client certificate on the connection
     * manager.
     * @see #registerClientCert(Context, String, boolean)
     */
    public synchronized void unregisterClientCert(
            String clientCertAlias, boolean trustAllServerCerts) {
        SchemeRegistry registry = getSchemeRegistry();
        String schemeName = makeSchemeForClientCert(clientCertAlias, trustAllServerCerts);
        Scheme existing = registry.get(schemeName);
        if (existing != null) {
            registry.unregister(schemeName);
        }
    }

    /**
     * Builds a custom scheme name to be used in a connection manager according to the connection
     * parameters.
     */
    public static String makeScheme(
            boolean useSsl, boolean trustAllServerCerts, String clientCertAlias) {
        if (clientCertAlias != null) {
            return makeSchemeForClientCert(clientCertAlias, trustAllServerCerts);
        } else {
            return useSsl ? (trustAllServerCerts ? "httpts" : "https") : "http";
        }
    }

    /**
     * Builds a unique scheme name for an SSL connection that uses a client user certificate.
     */
    private static String makeSchemeForClientCert(
            String clientCertAlias, boolean trustAllServerCerts) {
        String safeAlias = SSLUtils.escapeForSchemeName(clientCertAlias);
        return (trustAllServerCerts ? "httpts" : "https") + "+clientCert+" + safeAlias;
    }

    /**
     * @param since A timestamp in millis from epoch from which to check
     * @return whether or not this connection manager has detected any unsatisfied requests for
     *     a client SSL certificate by any servers
     */
    public synchronized boolean hasDetectedUnsatisfiedCertReq(long since) {
        return mTrackingKeyManager.getLastCertReqTime() >= since;
    }
}
