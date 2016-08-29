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

package com.vmware.admiral.compute.container;

import static com.vmware.admiral.common.util.UriUtilsExtended.getReverseProxyUri;
import static com.vmware.xenon.common.UriUtils.extendUri;

import java.net.URI;
import java.util.Map;

import com.vmware.admiral.common.ManagementUriParts;
import com.vmware.admiral.common.util.UriUtilsExtended;
import com.vmware.admiral.compute.container.ContainerService.ContainerState;
import com.vmware.xenon.common.Operation;
import com.vmware.xenon.common.UriUtils;

/**
 * Reverse proxy for container to it's shell, accessible through a shell agent on it's host.
 */
public class ContainerShellService extends AbstractShellContainerService {

    public static final String SELF_LINK = ManagementUriParts.CONTAINER_SHELL;
    public static final String CONTAINER_ID_QUERY_PARAM = "id";
    public static final String SHELL_PATH = "shell";

    @Override
    public void handleGet(Operation get) {
        Map<String, String> params = UriUtils.parseUriQueryParams(get.getUri());
        String containerDocumentId = params.remove(CONTAINER_ID_QUERY_PARAM);
        if (containerDocumentId == null || containerDocumentId.isEmpty()) {
            get.fail(new IllegalArgumentException(
                    "URL parameter 'id' expected with container id as value."));
            return;
        }
        loadContainerShellURL(containerDocumentId, get);
    }

    private void loadContainerShellURL(String containerDocumentId, Operation op) {
        String path = UriUtilsExtended.buildUriPath(ContainerFactoryService.SELF_LINK,
                containerDocumentId);
        sendRequest(Operation.createGet(this, path).setCompletion((o, e) -> {
            if (e != null) {
                op.fail(e);
                return;
            }
            ContainerState containerState = o.getBody(ContainerState.class);
            loadContainerShellURI(containerState.parentLink, op, (uri -> {
                URI shellUri = extendUri(extendUri(uri, SHELL_PATH), containerState.id);
                shellUri = getReverseProxyUri(shellUri);
                op.setContentType(Operation.MEDIA_TYPE_TEXT_PLAIN);
                op.setBody(shellUri.toString()).complete();
            }));
        }));
    }
}
