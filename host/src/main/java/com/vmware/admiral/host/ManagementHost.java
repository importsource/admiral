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

package com.vmware.admiral.host;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

import io.swagger.models.Info;

import com.vmware.admiral.service.common.AbstractTaskStatefulService;
import com.vmware.admiral.service.common.AuthBootstrapService;
import com.vmware.photon.controller.model.resources.ResourcePoolService;
import com.vmware.xenon.common.CommandLineArgumentParser;
import com.vmware.xenon.common.LoaderFactoryService;
import com.vmware.xenon.common.LoaderService;
import com.vmware.xenon.common.Operation;
import com.vmware.xenon.common.Operation.AuthorizationContext;
import com.vmware.xenon.common.OperationProcessingChain;
import com.vmware.xenon.common.Service;
import com.vmware.xenon.common.ServiceHost;
import com.vmware.xenon.common.UriUtils;
import com.vmware.xenon.common.Utils;
import com.vmware.xenon.services.common.AuthCredentialsService;
import com.vmware.xenon.services.common.ServiceUriPaths;
import com.vmware.xenon.swagger.SwaggerDescriptorService;

/**
 * Stand alone process entry point for management of infrastructure and applications.
 */
public class ManagementHost extends ServiceHost {

    /** Flag to start a mock adapter instance useful for integration tests */
    public boolean startMockHostAdapterInstance;

    /**
     * Users configuration file (full path). Specifying a file automatically enables Xenon's Authx
     * services.
     */
    public String localUsers;

    /**
     * Flag to start an etcd emulator service useful for enabling overlay networking capabilities
     * without an external KV store service
     */
    public boolean startEtcdEmulator;

    private static Map<Class<? extends Service>, Class<? extends OperationProcessingChain>> chains = new HashMap<>();

    /**
     * Add concrete {@link OperationProcessingChain} implementations that prevent the deletion of
     * referenced states.
     */
    static {
        chains.put(AuthCredentialsService.class, AuthCredentialsOperationProcessingChain.class);
        chains.put(ResourcePoolService.class, ResourcePoolOperationProcessingChain.class);
    }

    public static void main(String[] args) throws Throwable {
        ManagementHost h = new ManagementHost();
        h.initializeHostAndServices(args);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            h.log(Level.WARNING, "Host stopping ...");
            h.stop();
            h.log(Level.WARNING, "Host is stopped");
        }));
    }

    ManagementHost initializeHostAndServices(String[] args) throws Throwable {
        log(Level.INFO, "Initializing ...");
        initialize(args);

        log(Level.INFO, "Starting ...");
        start();

        log(Level.INFO, "Setting authorization context ...");
        // Set system user's authorization context to allow the services start privileged access.
        setAuthorizationContext(getSystemAuthorizationContext());

        log(Level.INFO, "**** Management host starting ... ****");

        startEnataiProvisioningServices();
        startManagementServices();
        startSwaggerService();

        log(Level.INFO, "**** Management host started. ****");

        log(Level.INFO, "**** Enabling dynamic service loading... ****");

        enableDynamicServiceLoading();

        log(Level.INFO, "**** Dynamic service loading enabled. ****");

        // Clean up authorization context to avoid privileged access.
        setAuthorizationContext(null);

        return this;
    }

    @Override
    public ServiceHost initialize(String[] args) throws Throwable {
        CommandLineArgumentParser.parse(this, args);
        Arguments baseArgs = new Arguments();
        if (AuthBootstrapService.isAuthxEnabled(localUsers)) {
            baseArgs.isAuthorizationEnabled = true;
        }
        return super.initialize(args, baseArgs);
    }

    protected void startEnataiProvisioningServices() throws Throwable {
        this.log(Level.INFO, "Fabric services starting ...");
        HostInitPhotonModelServiceConfig.startServices(this);

        this.log(Level.INFO, "Fabric services started.");
    }

    /**
     * Start all services required to support management of infrastructure and applications.
     */
    protected void startManagementServices() throws Throwable {
        this.log(Level.INFO, "Management service starting ...");

        registerForServiceAvailability(AuthBootstrapService.startTask(this), true,
                AuthBootstrapService.FACTORY_LINK);

        HostInitCommonServiceConfig.startServices(this);
        HostInitComputeServicesConfig.startServices(this);
        HostInitRequestServicesConfig.startServices(this);
        HostInitImageServicesConfig.startServices(this);
        HostInitUiServicesConfig.startServices(this);
        HostInitAdapterServiceConfig.startServices(this, startMockHostAdapterInstance);
        HostInitRegistryAdapterServiceConfig.startServices(this);
        HostInitEtcdAdapterServiceConfig.startServices(this, startEtcdEmulator);
        HostInitContinuousDeliveryServicesConfig.startServices(this);

        this.log(Level.INFO, "Management services started.");
    }

    /**
     * Start Swagger service.
     */
    protected void startSwaggerService() {
        this.log(Level.INFO, "Swagger service starting ...");

        // Serve Swagger 2.0 compatible API description
        SwaggerDescriptorService swagger = new SwaggerDescriptorService();

        // Exclude some core services
        swagger.setExcludedPrefixes(
                "/core/transactions",
                "/core/node-groups");

        // Provide API metainfo
        Info apiInfo = new Info();
        apiInfo.setVersion("0.0.1");
        apiInfo.setTitle("Container Management");

        // TODO - TBD
        // apiInfo.setLicense(new License().name("Apache 2.0")
        // .url("https://github.com/vmware/xenon/blob/master/LICENSE"));
        // apiInfo.setContact(new Contact().url("https://github.com/vmware/xenon"));

        swagger.setInfo(apiInfo);

        // Serve swagger on default uri
        this.startService(swagger);
        this.log(Level.INFO, "Swagger service started. Checkout Swagger UI at: "
                + this.getPublicUri() + ServiceUriPaths.SWAGGER + "/ui");
    }

    /**
     * The directory from which services are dynamically loaded; see
     * {@link #enableDynamicServiceLoading()}
     */
    private static final String DYNAMIC_SERVICES_PATH = System.getProperty(
            ManagementHost.class.getPackage().getName() + ".dynamic_services_path",
            "/etc/xenon/dynamic-services");

    /**
     * Enable Xenon services to be dynamically loaded, by starting the LoaderService. TODO: This
     * code is not required for Admiral, but rather for other components that are implemented as
     * Xenon services, so most of this host startup logic could be extracted out of "admiral" into
     * a separate component that just starts Xenon, and then "admiral" and other components could
     * just instruct Xenon to load their services.
     */
    void enableDynamicServiceLoading() {
        // https://github.com/vmware/xenon/wiki/LoaderService#loader-service-host
        // 1. start the loader service
        startService(
                Operation.createPost(
                        UriUtils.buildUri(
                                this,
                                LoaderFactoryService.class)),
                new LoaderFactoryService());
        // 2. configure service discovery from DYNAMIC_SERVICES_PATH
        LoaderService.LoaderServiceState payload = new LoaderService.LoaderServiceState();
        payload.loaderType = LoaderService.LoaderType.FILESYSTEM;
        payload.path = DYNAMIC_SERVICES_PATH;
        payload.servicePackages = new HashMap<>();
        sendRequest(Operation.createPost(UriUtils.buildUri(this, LoaderFactoryService.class))
                .setBody(payload)
                .setReferer(getUri()));
    }

    @Override
    public ServiceHost startService(Operation post, Service service) {
        Class<? extends Service> serviceClass = service.getClass();
        if (chains.containsKey(serviceClass)) {
            try {
                service.setOperationProcessingChain(
                        chains.get(serviceClass).getDeclaredConstructor(serviceClass)
                                .newInstance(service));
            } catch (Exception e) {
                log(Level.SEVERE, "Failure: %s", Utils.toString(e));
            }
        }
        return super.startService(post, service);
    }

    @Override
    public ServiceHost start() throws Throwable {

        super.start();
        startDefaultCoreServicesSynchronously();

        return this;
    }

    @Override
    public void stop() {
        AbstractTaskStatefulService.stop();
        super.stop();
    }

    @Override
    public void setAuthorizationContext(AuthorizationContext context) {
        super.setAuthorizationContext(context);
    }

    @Override
    public AuthorizationContext getSystemAuthorizationContext() {
        return super.getSystemAuthorizationContext();
    }
}
