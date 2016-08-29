/*
 * Copyright (c) 2016 VMware, Inc. All Rights Reserved.
 *
 * This product is licensed to you under the Apache License, Version 2.0 (the "License").
 * You may not use this product except in compliance with the License.
 *
 * This product may include a number of subcomponents with separate copyright notices
 * and license terms. Your use of these subcomponents is subject to the terms and
 * conditions of the subcomponent's license, as noted in the LICENSE file.
 */

package com.vmware.admiral.service.common;

import static com.vmware.admiral.common.util.AssertUtil.assertNotEmpty;
import static com.vmware.admiral.common.util.PropertyUtils.mergeProperty;

import java.security.cert.X509Certificate;
import java.util.concurrent.TimeUnit;

import com.vmware.admiral.common.ManagementUriParts;
import com.vmware.admiral.common.util.CertificateUtil;
import com.vmware.admiral.common.util.ServiceUtils;
import com.vmware.admiral.common.util.SubscriptionManager;
import com.vmware.admiral.common.util.ValidationUtils;
import com.vmware.admiral.service.common.ConfigurationService.ConfigurationFactoryService;
import com.vmware.admiral.service.common.ConfigurationService.ConfigurationState;
import com.vmware.xenon.common.Operation;
import com.vmware.xenon.common.Service;
import com.vmware.xenon.common.ServiceDocumentDescription.PropertyIndexingOption;
import com.vmware.xenon.common.ServiceDocumentDescription.PropertyUsageOption;
import com.vmware.xenon.common.StatefulService;
import com.vmware.xenon.common.Utils;

/**
 * Trust Store service for storing and managing SSL Trust Certificates. The certificates could be
 * server certificates or Certificate Authorities (CA) in .PEM format.
 *
 * The Trust Certificates are used in the context of setting up SSL connection between client and
 * server. The client certificate keys are handled by AuthCredentialsService.
 */
public class SslTrustCertificateService extends StatefulService {
    public static final String FACTORY_LINK = ManagementUriParts.SSL_TRUST_CERTS;
    public static final String SSL_TRUST_LAST_UPDATED_DOCUMENT_KEY = "ssl.trust.last.updated.document";

    private volatile SubscriptionManager<SslTrustCertificateState> subscriptionManager;

    public static class SslTrustCertificateState extends MultiTenantDocument {

        public static final String FIELD_NAME_CERTIFICATE = "certificate";
        public static final String FIELD_NAME_RESOURCE_LINK = "resourceLink";
        public static final String FIELD_NAME_SUBSCRIPTION_LINK = "subscriptionLink";

        /** (Required) The SSL trust certificate encoded into .PEM format. */
        @Documentation(description = "The SSL trust certificate encoded into .PEM format.")
        @PropertyOptions(
                indexing = { PropertyIndexingOption.STORE_ONLY },
                usage = { PropertyUsageOption.SINGLE_ASSIGNMENT })
        public String certificate;

        /** (Optional) Resource (compute, registry ...) associate with the trust certificate. */
        @Documentation(description = "Resource (compute, registry ...) associate with the trust certificate.")
        @PropertyOptions(
                usage = { PropertyUsageOption.OPTIONAL, PropertyUsageOption.LINK,
                        PropertyUsageOption.SINGLE_ASSIGNMENT })
        public String resourceLink;

        /** (Read Only) The common name of the certificate. */
        @Documentation(description = "The common name of the certificate.")
        @PropertyOptions(
                usage = { PropertyUsageOption.OPTIONAL, PropertyUsageOption.SINGLE_ASSIGNMENT })
        public String commonName;

        /** (Read Only) The issuer name of the certificate. */
        @Documentation(description = "The issuer name of the certificate.")
        @PropertyOptions(
                usage = { PropertyUsageOption.OPTIONAL, PropertyUsageOption.SINGLE_ASSIGNMENT })
        public String issuerName;

        /** (Read Only) The serial of the certificate. */
        @Documentation(description = "The serial of the certificate.")
        @PropertyOptions(
                usage = { PropertyUsageOption.OPTIONAL, PropertyUsageOption.SINGLE_ASSIGNMENT })
        public String serial;

        /** (Read Only) The fingerprint of the certificate in SHA-1 form. */
        @Documentation(description = "The fingerprint of the certificate in SHA-1 form. ")
        @PropertyOptions(
                usage = { PropertyUsageOption.OPTIONAL, PropertyUsageOption.SINGLE_ASSIGNMENT })
        public String fingerprint;

        /** (Read Only) The date since the certificate is valid. */
        @Documentation(description = "The date since the certificate is valid.")
        @PropertyOptions(
                usage = { PropertyUsageOption.OPTIONAL, PropertyUsageOption.SINGLE_ASSIGNMENT })
        public long validSince;

        /** (Read Only) The date until the certificate is valid. */
        @Documentation(description = "The date until the certificate is valid.")
        @PropertyOptions(
                usage = { PropertyUsageOption.OPTIONAL, PropertyUsageOption.SINGLE_ASSIGNMENT })
        public long validTo;

        /* (Internal) Set by the service when subscription for resource deletion is created */
        @Documentation(description = "Set by the service when subscription for resource deletion is created")
        @PropertyOptions(
                indexing = { PropertyIndexingOption.STORE_ONLY },
                usage = { PropertyUsageOption.SERVICE_USE, PropertyUsageOption.LINK,
                        PropertyUsageOption.SINGLE_ASSIGNMENT })
        public String subscriptionLink;

        public String getAlias() {
            return Service.getId(this.documentSelfLink);
        }

        public static void populateCertificateProperties(SslTrustCertificateState state,
                X509Certificate cert) {
            state.documentExpirationTimeMicros = TimeUnit.MILLISECONDS
                    .toMicros(cert.getNotAfter().getTime());

            state.commonName = CertificateUtil.getCommonName(cert.getSubjectDN());
            state.issuerName = CertificateUtil.getCommonName(cert.getIssuerDN());
            state.serial = cert.getSerialNumber() == null ? null : cert.getSerialNumber()
                    .toString();
            state.fingerprint = CertificateUtil.computeCertificateThumbprint(cert);
            state.validSince = cert.getNotBefore().getTime();
            state.validTo = cert.getNotAfter().getTime();
        }
    }

    public SslTrustCertificateService() {
        super(SslTrustCertificateState.class);
        super.toggleOption(ServiceOption.REPLICATION, true);
        super.toggleOption(ServiceOption.PERSISTENCE, true);
        super.toggleOption(ServiceOption.OWNER_SELECTION, true);
        super.toggleOption(ServiceOption.INSTRUMENTATION, true);
        super.toggleOption(ServiceOption.IDEMPOTENT_POST, true);
    }

    @Override
    public void handleCreate(Operation post) {
        if (!checkForBody(post)) {
            return;
        }

        SslTrustCertificateState state = post.getBody(SslTrustCertificateState.class);
        if (state.documentVersion > 0) {
            post.complete();
            return;
        }

        boolean validated = ValidationUtils.validate(post, () -> validateStateOnStart(state));
        if (!validated) {
            return;
        }
        post.complete();
        notifyLastUpdatedSslTrustDocumentService();
    }

    @Override
    public void handlePatch(Operation patch) {
        if (!checkForBody(patch)) {
            return;
        }

        SslTrustCertificateState body = patch.getBody(SslTrustCertificateState.class);
        SslTrustCertificateState state = getState(patch);
        String stateCert = state.certificate;
        state.certificate = mergeProperty(state.certificate, body.certificate);

        boolean validated = ValidationUtils.validate(patch, () -> validateStateOnStart(state));
        if (!validated) {
            return;
        }

        patch.setBody(state);

        if (isModified(patch, stateCert, body.certificate)) {
            patch.complete();
            notifyLastUpdatedSslTrustDocumentService();
        } else {
            patch.complete();
        }
    }

    @Override
    public void handlePut(Operation put) {
        if (!checkForBody(put)) {
            return;
        }

        SslTrustCertificateState body = put.getBody(SslTrustCertificateState.class);
        SslTrustCertificateState state = getState(put);
        // these properties can't be modified once set:
        body.subscriptionLink = state.subscriptionLink;
        body.resourceLink = state.resourceLink;

        boolean validated = ValidationUtils.validate(put, () -> validateStateOnStart(body));
        if (!validated) {
            return;
        }

        this.setState(put, body);
        put.setBody(body);
        if (isModified(put, state.certificate, body.certificate)) {
            put.complete();
            notifyLastUpdatedSslTrustDocumentService();
        } else {
            put.complete();
        }
    }

    @Override
    public void handleDelete(Operation delete) {
        SslTrustCertificateState state = getState(delete);
        if (state == null || state.documentSelfLink == null) {
            delete.complete();
            return;
        }
        unsubscribeForResourceDeletion(state);
        delete.complete();
        notifyLastUpdatedSslTrustDocumentService();
    }

    private void validateStateOnStart(SslTrustCertificateState state) throws Exception {
        assertNotEmpty(state.certificate, "certificate");

        X509Certificate[] certificates = CertificateUtil.createCertificateChain(state.certificate);

        CertificateUtil.validateCertificateChain(certificates);

        // Populate the certificate properties based on the first (end server) certificate
        X509Certificate endCertificate = certificates[0];
        SslTrustCertificateState.populateCertificateProperties(state, endCertificate);

        subscribeForResourceDeletion(state);
    }

    private boolean isModified(Operation op, String stateCert, String bodyCert) {
        if (stateCert != null && stateCert.equals(bodyCert)) {
            op.setStatusCode(Operation.STATUS_CODE_NOT_MODIFIED);
            return false;
        }
        return true;
    }

    /*
     * Subscribe for the associated resource (server, computeHost, registry...). When the associated
     * resource is deleted, the SSL trust certificate also is deleted. This will be valid only for
     * imported server certificates as part of the compute host registration. CA certificates will
     * not have a link to associated resource and will be automatically removed only after the
     * certificate expires or by explicit delete operation..
     */
    private void subscribeForResourceDeletion(SslTrustCertificateState state) {
        if (state.subscriptionLink != null || state.resourceLink == null) {
            return;
        }

        subscriptionManager = getSubscriptionManager(state);
        state.subscriptionLink = subscriptionManager.start((r) -> {
            if (r.isDelete()) {
                logInfo("Self delete based on deleted reference: [%s].",
                        r.getResult() == null ? "n.a." : r.getResult().documentSelfLink);
                ServiceUtils.sendSelfDelete(this);
            }
        });
    }

    private void unsubscribeForResourceDeletion(SslTrustCertificateState state) {
        try {
            if (state != null && state.subscriptionLink != null) {
                getSubscriptionManager(state).close();
            }
        } catch (Throwable e) {
            logWarning("Error unsubscribing during deletion. Error: %s", e.getMessage());
        }
    }

    private SubscriptionManager<SslTrustCertificateState> getSubscriptionManager(
            SslTrustCertificateState state) {
        if (subscriptionManager == null) {
            subscriptionManager = new SubscriptionManager<SslTrustCertificateState>(this.getHost(),
                    getSelfId(), state.resourceLink, SslTrustCertificateState.class);
        } else {
            // set the subscription link in case the service has been restarted
            // (or the service on another node has become active)
            // and the subscription has already been completed.
            subscriptionManager.setSubscriptionLink(state.subscriptionLink);
        }
        return subscriptionManager;
    }

    private void notifyLastUpdatedSslTrustDocumentService() {
        ConfigurationState body = new ConfigurationState();
        body.key = SslTrustCertificateService.SSL_TRUST_LAST_UPDATED_DOCUMENT_KEY;
        body.documentSelfLink = body.key;
        body.value = getSelfLink();

        sendRequest(Operation.createPost(this, ConfigurationFactoryService.SELF_LINK)
                .setBody(body)
                .setCompletion((o, e) -> {
                    if (e != null) {
                        logWarning(
                                "Error notifying last updated ssl trust document: %s. Error: %s",
                                getSelfLink(), Utils.toString(e));
                        return;
                    }

                    logFine("Last updated ssl trust document completed.");
                }));
    }
}
