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

import * as actions from 'actions/Actions';
import services from 'core/services';
import constants from 'core/constants';
import RequestsStore from 'stores/RequestsStore';
import NotificationsStore from 'stores/NotificationsStore';
import EventLogStore from 'stores/EventLogStore';
import ContextPanelStoreMixin from 'stores/mixins/ContextPanelStoreMixin';
import CrudStoreMixin from 'stores/mixins/CrudStoreMixin';
import utils from 'core/utils';
import imageUtils from 'core/imageUtils';
import links from 'core/links';

let ContainersStore;

function getHostDocumentId(container) {
  var host = container.parentLink.substring('/resources/compute/'.length);
  return utils.extractHostId(host);
}

function getContainersImageIcons(containers) {
  let icons = new Set();
  for (var c in containers) {
    if (containers.hasOwnProperty(c)) {
      icons.add(imageUtils.getImageIconLink(containers[c].image));
    }
  }
  return [...icons];
}

function getHostByLink(hosts, hostLink) {
  if (!hosts) {
    return null;
  }

  return hosts.find((host) => {
    return host.documentSelfLink === hostLink;
  });
}

function enhanceContainer(container) {
  container.icon = imageUtils.getImageIconLink(container.image);
  container.documentId = utils.getDocumentId(container.documentSelfLink);

  container.hostDocumentId = getHostDocumentId(container);
  container.type = constants.CONTAINERS.TYPES.SINGLE;

  if (container.attributes) {
    container.attributes.Config = JSON.parse(container.attributes.Config);
    container.attributes.NetworkSettings = JSON.parse(container.attributes.NetworkSettings);
    container.attributes.HostConfig = JSON.parse(container.attributes.HostConfig);
  }

  return container;
}

function decorateContainerHostName(container, hosts) {
  let host = getHostByLink(hosts, container.parentLink);
  container.hostName = host ? utils.getHostName(host) : null;
  container.hostAddress = host && host.address;

  return container;
}

function enhanceCompositeComponent(compositeComponent) {
  compositeComponent.icons = [];
  compositeComponent.documentId = utils.getDocumentId(compositeComponent.documentSelfLink);
  compositeComponent.type = constants.CONTAINERS.TYPES.COMPOSITE;

  return compositeComponent;
}

function getSelectedItemDetailsCursor() {
  var selectedItemDetailsCursor = this.selectFromData(['selectedItemDetails']);
  var selectedItemDetails = selectedItemDetailsCursor.get();
  if (!selectedItemDetails) {
    return null;
  }
  while (selectedItemDetails.selectedItemDetails) {
    selectedItemDetailsCursor = selectedItemDetailsCursor.select(['selectedItemDetails']);
    selectedItemDetails = selectedItemDetails.selectedItemDetails;
  }
  return selectedItemDetailsCursor;
}

function getSelectedContainerDetailsCursor() {
  var selectedItemDetailsCursor = getSelectedItemDetailsCursor.call(this);
  if (!selectedItemDetailsCursor ||
      selectedItemDetailsCursor.get().type !== constants.CONTAINERS.TYPES.SINGLE) {
    return null;
  }
  return selectedItemDetailsCursor;
}

function updateSelectedContainerDetails(path, value) {
  var selectedItemDetailsCursor = getSelectedContainerDetailsCursor.call(this);
  selectedItemDetailsCursor.setIn(path, value);
}

function mergeItems(items1, items2) {
  return items1.concat(items2)
                  .filter((item, index, self) =>
                    self.findIndex((c) => c.documentSelfLink === item.documentSelfLink) === index);
}

function findContextId(containers) {
  if (containers) {
    for (let key in containers) {
      if (!containers.hasOwnProperty(key)) {
        continue;
      }

      var container = containers[key];
      if (container.customProperties && container.customProperties.__composition_context_id) {
        return container.customProperties.__composition_context_id;
      }
    }
  }

  return null;
}

function makeClusterId(descriptionLink, compositeContextId, containers) {
  let clusterDescriptionLink = (descriptionLink.indexOf(links.CONTAINER_DESCRIPTIONS) > -1)
                      ? descriptionLink
                      : links.CONTAINER_DESCRIPTIONS + '/' + descriptionLink;

  if (!compositeContextId) {
    compositeContextId = findContextId(containers);
  }

  var clusterId = clusterDescriptionLink;
  if (compositeContextId != null) {
    clusterId += '__' + compositeContextId;
  }

  return clusterId;
}

function getDescriptionLinkFromClusterId(clusterId) {
  let containerDescriptionLink = clusterId;

  let idxSeparator = clusterId.indexOf('__');
  if (idxSeparator > -1) {
    containerDescriptionLink = clusterId.substring(0, idxSeparator);
  }

  return containerDescriptionLink;
}

function getContextIdFromClusterId(clusterId) {
  let contextId = null;

  let idxSeparator = clusterId.indexOf('__');
  if (idxSeparator > -1) {
    contextId = clusterId.substring(idxSeparator + 2, clusterId.length);
  }

  return contextId;
}

function makeClusterObject(clusterId, containers) {
  var clusterObject = {
    documentSelfLink: clusterId,
    descriptionLink: clusterId,
    name: containers ? containers[0].image : 'N/A',
    type: constants.CONTAINERS.TYPES.CLUSTER,
    containers: containers
  };

  clusterObject.icon = containers ? imageUtils.getImageIconLink(containers[0].image) : null;
  clusterObject.documentId = utils.getDocumentId(clusterId);

  return clusterObject;

}

function getClusterSize(containers) {
  var clusterSize = 0;
  if (containers) {
    for (let key in containers) {
      if (!containers.hasOwnProperty(key)) {
        continue;
      }

      clusterSize++;
    }
  }

  return clusterSize;
}

function isEverythingRemoved(selectedItem, operationType, removedIds) {
  if (!selectedItem) { // nothing is selected
    return false;
  }

  if (operationType !== constants.CONTAINERS.OPERATION.REMOVE) { // op is not remove
    return false;
  }

  if ((removedIds.length === 1)
        && selectedItem.type === constants.CONTAINERS.TYPES.COMPOSITE
        && selectedItem.documentId === removedIds[0]) {
    // the application itself has been deleted
    return true;
  }

  let previousItems = selectedItem.listView && selectedItem.listView.items;
  if (previousItems.length !== removedIds.length) {
    // not everything is deleted
    return false;

  } else {
    let remainingItems = previousItems.filter((item) => {
      return (removedIds.indexOf(item.documentId) === -1);
    });

    return remainingItems.length < 1;
  }
}

ContainersStore = Reflux.createStore({
  mixins: [ContextPanelStoreMixin, CrudStoreMixin],
  init: function() {
    NotificationsStore.listen((notifications) => {
      this.setInData(['contextView', 'notifications', constants.CONTEXT_PANEL.REQUESTS],
        notifications.runningRequestItemsCount);

      this.setInData(['contextView', 'notifications', constants.CONTEXT_PANEL.EVENTLOGS],
        notifications.latestEventLogItemsCount);

      this.emitChange();
    });

    RequestsStore.listen((requestsData) => {
      if (this.isContextPanelActive(constants.CONTEXT_PANEL.REQUESTS)) {
        this.setActiveItemData(requestsData);
        this.emitChange();
      }
    });

    EventLogStore.listen((eventlogsData) => {
      if (this.isContextPanelActive(constants.CONTEXT_PANEL.EVENTLOGS)) {
        this.setActiveItemData(eventlogsData);
        this.emitChange();
      }
    });
  },

  listenables: [actions.ContainerActions, actions.RegistryActions,
    actions.ContainersContextToolbarActions
  ],

  decorateContainers: function(result, isCategoryContainers, mergeWithExisting) {
    let itemsCount = result.totalCount;
    let nextPageLink = result.nextPageLink;

    let items = result.documentLinks.map((documentLink) => {
          return result.documents[documentLink];
        });

    if (isCategoryContainers) {
      items.forEach((container) => {
        enhanceContainer(container);
      });

      let previousItems = this.selectFromData(['listView', 'items']).get();

      let mergedItems = (previousItems && mergeWithExisting)
        ? mergeItems(previousItems.asMutable(), items) : items;

      this.setInData(['listView', 'items'], mergedItems);
      this.setInData(['listView', 'itemsLoading'], false);
      if (itemsCount !== undefined) {
        this.setInData(['listView', 'itemsCount'], itemsCount);
      }
      this.setInData(['listView', 'nextPageLink'], nextPageLink);

      this.emitChange();

      // retrieve host names
      this.getHostsForContainersCall(items).then((hosts) => {
        items.forEach((container) => {
          decorateContainerHostName(container, utils.resultToArray(hosts));
        });
        this.setInData(['listView', 'items'], mergedItems);
        this.emitChange();
      }).catch(this.onListError);

    } else {
      let compositeComponentsContainersCalls = [];

      items.forEach((item) => {
        enhanceCompositeComponent(item);
        compositeComponentsContainersCalls.push(
          services.loadContainersForCompositeComponent(item.documentSelfLink));
      });
      // Load containers of the current composite components
      Promise.all(compositeComponentsContainersCalls).then((containersResults) => {

        for (let i = 0; i < containersResults.length; i++) {
          let containers = containersResults[i].documentLinks.map((documentLink) => {
            return containersResults[i].documents[documentLink];
          });

          if (containers.length > 0) {
            // Assign the containers to the resp. composite component
            let compositeComponentLink = containers[0].compositeComponentLink;

            let compositeComponent = items.find((item) => {
              return item.documentSelfLink === compositeComponentLink;
            });

            if (compositeComponent) {
              compositeComponent.containers = containers;
              compositeComponent.icons = getContainersImageIcons(containers);
            }
          }
        }

        let previousItems = this.selectFromData(['listView', 'items']).get();
        let mergedItems = (previousItems && mergeWithExisting)
                            ? mergeItems(previousItems.asMutable(), items) : items;

        this.setInData(['listView', 'items'], mergedItems);
        this.setInData(['listView', 'itemsLoading'], false);
        if (itemsCount !== undefined) {
          this.setInData(['listView', 'itemsCount'], itemsCount);
        }
        this.setInData(['listView', 'nextPageLink'], nextPageLink);

        this.emitChange();
      }).catch(this.onListError);
    }
  },

  onOpenContainers: function(queryOptions, forceReload, keepContext) {
    var items = utils.getIn(this.data, ['listView', 'items']);
    if (!forceReload && items) {
      return;
    }

    this.setInData(['listView', 'queryOptions'], queryOptions);
    this.setInData(['selectedItem'], null);
    this.setInData(['selectedItemDetails'], null);

    if (!keepContext) {
      this.setInData(['contextView'], {});
    }

    var operation =
          this.requestCancellableOperation(constants.CONTAINERS.OPERATION.LIST, queryOptions);

    if (operation) {
      this.cancelOperations(constants.CONTAINERS.OPERATION.DETAILS,
        constants.CONTAINERS.OPERATION.STATS, constants.CONTAINERS.OPERATION.LOGS);
      this.setInData(['listView', 'itemsLoading'], true);
      this.setInData(['listView', 'error'], null);

      queryOptions = queryOptions || {
        $category: constants.CONTAINERS.SEARCH_CATEGORY.CONTAINERS
      };
      if (!queryOptions.$category) {
        queryOptions = $.extend({}, queryOptions);
        queryOptions.$category = constants.CONTAINERS.SEARCH_CATEGORY.CONTAINERS;
        this.setInData(['listView', 'queryOptions'], queryOptions);
        this.emitChange();
      }

      var isCategoryContainers =
        queryOptions.$category === constants.CONTAINERS.SEARCH_CATEGORY.CONTAINERS;

      operation.forPromise(isCategoryContainers
          ? services.loadContainers(queryOptions)
          : services.loadCompositeComponents(queryOptions)).then((result) => {

        return this.decorateContainers(result, isCategoryContainers, false);
      });
    }

    this.emitChange();
  },

  onOpenContainersNext: function(queryOptions, nextPageLink) {
    this.setInData(['listView', 'queryOptions'], queryOptions);

    var operation =
          this.requestCancellableOperation(constants.CONTAINERS.OPERATION.LIST, queryOptions);

    if (operation) {
      this.setInData(['listView', 'itemsLoading'], true);
      this.setInData(['listView', 'error'], null);

      queryOptions = queryOptions || {
        $category: constants.CONTAINERS.SEARCH_CATEGORY.CONTAINERS
      };
      var isCategoryContainers =
        queryOptions.$category === constants.CONTAINERS.SEARCH_CATEGORY.CONTAINERS;

      operation.forPromise(services.loadNextPage(nextPageLink))
        .then((result) => {

          return this.decorateContainers(result, isCategoryContainers, true);
        });
    }

    this.emitChange();
  },

  onCloseContainers: function() {
    this.setInData(['listView', 'items'], []);
    this.setInData(['listView', 'itemsLoading'], false);
    this.setInData(['listView', 'itemsCount'], 0);
    this.setInData(['listView', 'nextPageLink'], null);
    this.setInData(['listView', 'hosts'], null);
    this.setInData(['listView', 'error'], null);
  },

  onOpenContainerDetails: function(containerId, clusterId, compositeComponentId) {
    this.cancelOperations(constants.CONTAINERS.OPERATION.DETAILS,
      constants.CONTAINERS.OPERATION.STATS, constants.CONTAINERS.OPERATION.LOGS);

    var operation = this.requestCancellableOperation(constants.CONTAINERS.OPERATION.DETAILS);

    if (compositeComponentId) {
      this.loadCompositeComponent(compositeComponentId, operation);
    }
    if (clusterId) {
      this.loadCluster(clusterId, compositeComponentId, operation);
    }

    this.loadContainer(containerId, clusterId, compositeComponentId, operation);
  },

  onOpenClusterDetails: function(clusterId, compositeComponentId) {

    this.cancelOperations(constants.CONTAINERS.OPERATION.DETAILS,
      constants.CONTAINERS.OPERATION.STATS, constants.CONTAINERS.OPERATION.LOGS);

    var operation = this.requestCancellableOperation(constants.CONTAINERS.OPERATION.DETAILS);

    if (compositeComponentId) {
      this.loadCompositeComponent(compositeComponentId, operation);
    }

    this.loadCluster(clusterId, compositeComponentId, operation, true);
  },

  onOpenCompositeContainerDetails: function(compositeComponentId) {
    this.cancelOperations(constants.CONTAINERS.OPERATION.DETAILS,
      constants.CONTAINERS.OPERATION.STATS, constants.CONTAINERS.OPERATION.LOGS);

    var operation = this.requestCancellableOperation(constants.CONTAINERS.OPERATION.DETAILS);

    this.loadCompositeComponent(compositeComponentId, operation, true);
  },

  onRefreshContainer: function() {
    var selectedContainerDetails = getSelectedContainerDetailsCursor.call(this).get();

    if (!selectedContainerDetails || !selectedContainerDetails.documentId) {
      return;
    }

    var operation = this.requestCancellableOperation(constants.CONTAINERS.OPERATION.DETAILS);

    if (operation) {
      operation.forPromise(services.loadContainer(selectedContainerDetails.documentId))
        .then((container) => {
          enhanceContainer(container);
          updateSelectedContainerDetails.call(this, ['instance'], container);
          this.emitChange();

          services.loadHostByLink(container.parentLink).then((host) => {
            decorateContainerHostName(container, [host]);

            updateSelectedContainerDetails.call(this, ['instance'], container);
            this.emitChange();
          });
        });
    }
  },

  onRefreshContainerStats: function() {
    var selectedContainerDetails = getSelectedContainerDetailsCursor.call(this).get();

    if (!selectedContainerDetails || !selectedContainerDetails.documentId) {
      return;
    }

    var operation = this.requestCancellableOperation(constants.CONTAINERS.OPERATION.STATS);
    if (operation) {
      operation.forPromise(services.loadContainerStats(selectedContainerDetails.documentId))
        .then((stats) => {
          updateSelectedContainerDetails.call(this, ['statsLoading'], false);
          updateSelectedContainerDetails.call(this, ['stats'], stats);
          this.emitChange();
        });
    }
  },

  onRefreshContainerLogs: function() {
    var selectedContainerDetails = getSelectedContainerDetailsCursor.call(this).get();

    if (!selectedContainerDetails || !selectedContainerDetails.documentId) {
      return;
    }

    var operation = this.requestCancellableOperation(constants.CONTAINERS.OPERATION.LOGS);
    if (operation) {
      operation.forPromise(services.loadContainerLogs(selectedContainerDetails.documentId,
          selectedContainerDetails.logsSettings.sinceDuration))
        .then((logs) => {
          updateSelectedContainerDetails.call(this, ['logsLoading'], false);
          updateSelectedContainerDetails.call(this, ['logs'], logs);
          this.emitChange();
        });
    }
  },

  onChangeLogsSinceDuration: function(durationMs) {
    updateSelectedContainerDetails.call(this, ['logsSettings', 'sinceDuration'], durationMs);
    this.emitChange();
  },

  onStartContainer: function(containerId) {

    services.startContainer(containerId)
      .then((startContainerRequest) => {

        this.openToolbarItem(constants.CONTEXT_PANEL.REQUESTS, RequestsStore.getData());
        actions.RequestsActions.requestCreated(startContainerRequest);
      });
  },

  onStopContainer: function(containerId) {

    services.stopContainer(containerId)
      .then((stopContainerRequest) => {

        this.openToolbarItem(constants.CONTEXT_PANEL.REQUESTS, RequestsStore.getData());
        actions.RequestsActions.requestCreated(stopContainerRequest);
      });
  },

  onRemoveContainer: function(containerId) {

    services.removeContainer(containerId)
      .then((removalRequest) => {

        this.openToolbarItem(constants.CONTEXT_PANEL.REQUESTS, RequestsStore.getData());
        actions.RequestsActions.requestCreated(removalRequest);
      });
  },

  onStartCompositeContainer: function(compositeId) {

    services.startCompositeContainer(compositeId)
      .then((startContainerRequest) => {

        this.openToolbarItem(constants.CONTEXT_PANEL.REQUESTS, RequestsStore.getData());
        actions.RequestsActions.requestCreated(startContainerRequest);
      });
  },

  onStopCompositeContainer: function(compositeId) {

    services.stopCompositeContainer(compositeId)
      .then((stopContainerRequest) => {

        this.openToolbarItem(constants.CONTEXT_PANEL.REQUESTS, RequestsStore.getData());
        actions.RequestsActions.requestCreated(stopContainerRequest);
      });
  },

  onRemoveCompositeContainer: function(compositeId) {

    services.removeCompositeContainer(compositeId)
      .then((removalRequest) => {

        this.openToolbarItem(constants.CONTEXT_PANEL.REQUESTS, RequestsStore.getData());
        actions.RequestsActions.requestCreated(removalRequest);
      });
  },

  onStartCluster: function(clusterContainers) {
    services.startCluster(clusterContainers).then((startClusterRequest) => {

      this.openToolbarItem(constants.CONTEXT_PANEL.REQUESTS, RequestsStore.getData());
      actions.RequestsActions.requestCreated(startClusterRequest);
    });
  },

  onStopCluster: function(clusterContainers) {
    services.stopCluster(clusterContainers).then((stopClusterRequest) => {

      this.openToolbarItem(constants.CONTEXT_PANEL.REQUESTS, RequestsStore.getData());
      actions.RequestsActions.requestCreated(stopClusterRequest);
    });
  },

  onRemoveCluster: function(clusterContainers) {
    services.removeCluster(clusterContainers).then((removalRequest) => {

      this.openToolbarItem(constants.CONTEXT_PANEL.REQUESTS, RequestsStore.getData());
      actions.RequestsActions.requestCreated(removalRequest);
    });
  },

  onStartContainerDetails: function(containerId) {

    this.clearOperationFailure();

    services.startContainer(containerId)
      .then((startContainerRequest) => {
        var cursor = getSelectedContainerDetailsCursor.call(this);
        if (cursor.getIn(['documentId']) === containerId) {
          cursor.setIn(['operationInProgress'], constants.CONTAINERS.OPERATION.START);
        }
        this.emitChange();

        actions.RequestsActions.requestCreated(startContainerRequest);
      });
  },

  onStopContainerDetails: function(containerId) {

    this.clearOperationFailure();

    services.stopContainer(containerId)
      .then((stopContainerRequest) => {
        var cursor = getSelectedContainerDetailsCursor.call(this);
        if (cursor.getIn(['documentId']) === containerId) {
          cursor.setIn(['operationInProgress'], constants.CONTAINERS.OPERATION.STOP);
        }
        this.emitChange();

        actions.RequestsActions.requestCreated(stopContainerRequest);
      });
  },

  onRemoveContainerDetails: function(containerId) {

    this.clearOperationFailure();

    services.removeContainer(containerId)
      .then((removalRequest) => {
        var cursor = getSelectedContainerDetailsCursor.call(this);
        if (cursor.getIn(['documentId']) === containerId) {
          cursor.setIn(['operationInProgress'], constants.CONTAINERS.OPERATION.REMOVE);
        }
        this.emitChange();

        actions.RequestsActions.requestCreated(removalRequest);
      });
  },

  backFromContainerAction: function(operationType, resourceIds) {
    var cursor = getSelectedContainerDetailsCursor.call(this);

    if ((cursor != null) && (resourceIds.length === 1)
          && cursor.getIn(['documentId']) === resourceIds[0]
          && cursor.getIn(['operationInProgress'])) {
      // Refresh Container Details
      cursor.setIn(['operationInProgress'], null);

      if (operationType !== constants.CONTAINERS.OPERATION.REMOVE) {

        actions.ContainerActions.refreshContainer();
      } else {
        return this.refreshView(cursor.parent(), true, operationType, resourceIds);
      }
    } else {
      var lastSelectedItemDetailsCursor = getSelectedItemDetailsCursor.call(this);

      return this.refreshView(lastSelectedItemDetailsCursor, false, operationType, resourceIds);
    }
  },

  refreshView: function(selectedItemDetailsCursor, navigateToParent, operationType, resourceIds) {

    if ((selectedItemDetailsCursor != null) && (selectedItemDetailsCursor.get() != null)) {

      return this.showSelectedDetailsView(selectedItemDetailsCursor, navigateToParent,
                                            operationType, resourceIds);
    } else {
      // Refresh Containers List view if nothing has been selected
      this.navigateToContainersListView(navigateToParent);
    }
  },

  showSelectedDetailsView(selectedItemDetailsCursor, navigateToParent, operationType,
                          resourceIds) {
    let selectedItemDetails = selectedItemDetailsCursor.get();

    if (isEverythingRemoved.call(this, selectedItemDetails, operationType, resourceIds)) {

      if (selectedItemDetails.type === constants.CONTAINERS.TYPES.COMPOSITE) {
        this.navigateToContainersListView(true);

      } else if (selectedItemDetails.type === constants.CONTAINERS.TYPES.CLUSTER) {
        let parentCursor = selectedItemDetailsCursor.parent();
        let parentDetails = (parentCursor != null) ? parentCursor.get() : null;

        if (parentDetails && parentDetails.type === constants.CONTAINERS.TYPES.COMPOSITE) {
          let isTheOnlyElemInComposite = (parentDetails.listView.items.length === 1);

          if (isTheOnlyElemInComposite) {
            this.navigateToContainersListView(true);
          } else {
            actions.NavigationActions.openCompositeContainerDetails(parentDetails.documentId);
          }
        }
      }
    } else {

      return this.showLastShownView(selectedItemDetailsCursor, navigateToParent);
    }
  },

  showLastShownView(selectedItemDetailsCursor, navigateToParent) {
    let selectedItemDetails = selectedItemDetailsCursor.get();

    // Refresh the last shown view of a selected item
    if (selectedItemDetails.type === constants.CONTAINERS.TYPES.CLUSTER) {
      let clusterId = selectedItemDetails.documentId;
      let parentCursor = selectedItemDetailsCursor.parent();
      let compositeContainerId = (parentCursor != null) ? parentCursor.get().documentId : null;

      if (navigateToParent) {
        actions.NavigationActions.openClusterDetails(clusterId, compositeContainerId);
      } else {
        actions.ContainerActions.openClusterDetails(clusterId, compositeContainerId);
      }

    } else if (selectedItemDetails.type === constants.CONTAINERS.TYPES.COMPOSITE) {

      if (navigateToParent) {
        actions.NavigationActions.openCompositeContainerDetails(selectedItemDetails.documentId);
      } else {
        actions.ContainerActions.openCompositeContainerDetails(selectedItemDetails.documentId);
      }

    } else if (selectedItemDetails.type === constants.CONTAINERS.TYPES.SINGLE) {
      this.navigateToContainersListView(navigateToParent);

    } else if (selectedItemDetails.listView) {
      this.navigateToContainersListView(navigateToParent);
    }
  },

  navigateToContainersListView: function(navigateToParent) {
    var queryOptions = utils.getIn(this.data, ['listView', 'queryOptions']);

    if (navigateToParent) {
      actions.NavigationActions.openContainers(queryOptions);
    } else {
      actions.ContainerActions.openContainers(queryOptions, true, true);
    }
  },

  onOperationCompleted: function(operationType, resourceIds) {
    if (operationType === constants.CONTAINERS.OPERATION.START) {
      // Container Started
      this.backFromContainerAction(operationType, resourceIds);

    } else if (operationType === constants.CONTAINERS.OPERATION.STOP) {
      // Container Stopped
      this.backFromContainerAction(operationType, resourceIds);

    } else if (operationType === constants.CONTAINERS.OPERATION.CREATE) {
      // Container created
      actions.ContainersContextToolbarActions.openToolbarRequests();

    } else if (operationType === constants.CONTAINERS.OPERATION.REMOVE) {
      // Container Removed
      this.backFromContainerAction(operationType, resourceIds);

    } else if (operationType === constants.CONTAINERS.OPERATION.CLUSTERING) {
      //  Clustering day2 op
      this.backFromContainerAction(operationType, resourceIds);

    } else if (operationType === constants.CONTAINERS.OPERATION.DEFAULT) {
      // default - refresh current view on operation finished
      this.backFromContainerAction(operationType, resourceIds);
    }
  },

  onOperationFailed: function(operationType, resourceIds) {
    // Currently only needed for the Details view
    var cursor = getSelectedContainerDetailsCursor.call(this);
    if (cursor && cursor.getIn(['documentId']) === resourceIds[0] &&
      cursor.getIn(['operationInProgress'])) {
      cursor.setIn(['operationInProgress'], null);
      cursor.setIn(['operationFailure'], operationType);

      actions.ContainerActions.refreshContainer();
    }
  },

  clearOperationFailure: function() {
    this.setInData(['selectedItemDetails', 'operationFailure'], null);
  },

  selectComponent: function(containerId, clusterId, compositeComponentId) {
    var cursor = this.selectFromData([]);

    if (compositeComponentId) {
      cursor = cursor.select(['selectedItemDetails']);
      let value = cursor.get();
      if (!value || value.type !== constants.CONTAINERS.TYPES.COMPOSITE ||
        value.documentId !== compositeComponentId) {
        return null;
      }
    }

    if (clusterId) {
      cursor = cursor.select(['selectedItemDetails']);
      let value = cursor.get();
      if (!value || value.type !== constants.CONTAINERS.TYPES.CLUSTER ||
        value.documentId !== clusterId) {
        return null;
      }
    }

    if (containerId) {
      cursor = cursor.select(['selectedItemDetails']);
      let value = cursor.get();
      if (!value || value.type !== constants.CONTAINERS.TYPES.SINGLE ||
        value.documentId !== containerId) {
        return null;
      }
    }

    return cursor;
  },

  loadCompositeComponent: function(compositeComponentId, operation, force) {
    var parentCursor = this.selectComponent(null, null, null);

    var currentCompositeComponent = parentCursor.select(['selectedItemDetails']).get();

    if (currentCompositeComponent &&
        currentCompositeComponent.documentId === compositeComponentId && !force) {
      return;
    }

    var compositeComponent = {
      documentId: compositeComponentId,
      name: compositeComponentId,
      type: constants.CONTAINERS.TYPES.COMPOSITE
    };

    var compositeComponentDetails = {
      documentId: compositeComponentId,
      type: constants.CONTAINERS.TYPES.COMPOSITE,
      listView: {
        itemsLoading: true
      }
    };

    if (currentCompositeComponent &&
        currentCompositeComponent.documentId === compositeComponentId) {
      compositeComponentDetails.listView.items = currentCompositeComponent.listView.items;
    }

    parentCursor.setIn(['selectedItem'], compositeComponent);
    parentCursor.setIn(['selectedItemDetails'], compositeComponentDetails);
    // clear errors
    parentCursor.setIn(['selectedItemDetails', 'error'], null);

    this.emitChange();

    operation.forPromise(Promise.all([
      services.loadCompositeComponent(compositeComponentId),
      services.loadContainersForCompositeComponent(compositeComponentId)
    ])).then(([retrievedCompositeComponent, childContainersResult]) => {

      var childContainers = utils.resultToArray(childContainersResult.documents ?
          childContainersResult.documents : childContainersResult);

      enhanceCompositeComponent(retrievedCompositeComponent);
      retrievedCompositeComponent.icons = getContainersImageIcons(childContainers);
      parentCursor.select(['selectedItem']).merge(retrievedCompositeComponent);

      childContainers.forEach((childContainer) => {
        enhanceContainer(childContainer);
      });

      var items = this.aggregateClusterNodes(childContainers);

      parentCursor.select(['selectedItemDetails'])
        .setIn(['listView', 'items'], items)
        .setIn(['listView', 'itemsLoading'], false);

      this.emitChange();

      this.getHostsForContainersCall(childContainers).then((hosts) => {
        childContainers.forEach((childContainer) => {
          decorateContainerHostName(childContainer, utils.resultToArray(hosts));
        });

        parentCursor.select(['selectedItemDetails'])
          .setIn(['listView', 'items'], items);
        this.emitChange();
      });

    }).catch(this.onGenericDetailsError);
  },

  loadCluster: function(clusterId, compositeComponentId, operation, force) {
    var parentCursor = this.selectComponent(null, null, compositeComponentId);

    var currentClusterComponent = parentCursor.select(['selectedItemDetails']).get();

    if (currentClusterComponent && currentClusterComponent.documentId === clusterId && !force) {
      return;
    }

    var clusterComponent = makeClusterObject(clusterId);

    var clusterComponentDetails = {
      documentId: clusterComponent.documentId,
      descriptionLink: clusterComponent.descriptionLink,
      type: constants.CONTAINERS.TYPES.CLUSTER,

      listView: {
        itemsLoading: true
      }
    };

    if (currentClusterComponent && currentClusterComponent.documentId === clusterId) {
      clusterComponentDetails.listView.items = currentClusterComponent.listView.items;
    }

    parentCursor.setIn(['selectedItem'], clusterComponent);
    parentCursor.setIn(['selectedItemDetails'], clusterComponentDetails);
    this.emitChange();

    // query containers by description link
    operation.forPromise(
      services.loadClusterContainers(
        getDescriptionLinkFromClusterId(clusterId), compositeComponentId))
      .then((containers) => {

        if (parentCursor.get().type === constants.CONTAINERS.TYPES.COMPOSITE) {
          parentCursor.setIn(['expanded', true]);
        }
        var items = utils.resultToArray(containers);
        items.forEach((container) => {
          enhanceContainer(container);
        });

        let cluster = makeClusterObject(clusterId, items);

        parentCursor.setIn(['selectedItem'], cluster);
        parentCursor.select(['selectedItemDetails'])
          .setIn(['listView', 'items'], items)
          .setIn(['listView', 'itemsLoading'], false);

        this.emitChange();

        this.getHostsForContainersCall(items).then((hosts) => {
          items.forEach((container) => {
            decorateContainerHostName(container, utils.resultToArray(hosts));
          });
          parentCursor.select(['selectedItemDetails']).setIn(['listView', 'items'], items);
          this.emitChange();
        });
      }).catch(this.onGenericDetailsError);
  },

  loadContainer: function(containerId, clusterId, compositeComponentId, operation) {
    var parentCursor = this.selectComponent(null, clusterId, compositeComponentId);
    // If switching between views, there will be a short period that we show old data,
    // until the new one is loaded.
    var currentItemDetailsCursor = parentCursor.select(['selectedItemDetails']);
    currentItemDetailsCursor.merge({
      logsSettings: {
        sinceDuration: constants.CONTAINERS.LOGS.SINCE_DURATIONS[0]
      },
      type: constants.CONTAINERS.TYPES.SINGLE,
      documentId: containerId,
      logsLoading: true,
      statsLoading: true
    });

    var currentItemCursor = parentCursor.select(['selectedItem']);
    currentItemCursor.merge({
      type: constants.CONTAINERS.TYPES.SINGLE,
      documentId: containerId
    });
    this.emitChange();

    operation.forPromise(services.loadContainer(containerId))
      .then((container) => {
        enhanceContainer(container);

        if (parentCursor.get().type === constants.CONTAINERS.TYPES.CLUSTER ||
          parentCursor.get().type === constants.CONTAINERS.TYPES.COMPOSITE) {
          parentCursor.setIn(['expanded', true]);
        }

        currentItemCursor.merge(container);
        currentItemDetailsCursor.setIn(['instance'], container);
        this.emitChange();

        services.loadHostByLink(container.parentLink).then((host) => {
          decorateContainerHostName(container, [host]);
          currentItemCursor.merge(container);
          currentItemDetailsCursor.setIn(['instance'], container);
          this.emitChange();

          if (container.exposedServiceLink) {
            services.loadExposedService(container.exposedServiceLink).then((exposedService) => {
              services.loadHostByLink(exposedService.hostLink).then((host) => {

                if (currentItemDetailsCursor.getIn(['documentId']) === containerId) {
                  exposedService.hostname = utils.getURLParts(host.address).host;

                  currentItemDetailsCursor.setIn(['exposedService'], exposedService);
                  this.emitChange();
                }
              });
            });
          }
        });
      }).catch(this.onGenericDetailsError);
  },

  aggregateClusterNodes: function(nodes) {
    let nodesByClusterId = [];
    for (let n in nodes) {

      if (!nodes.hasOwnProperty(n)) {
        continue;
      }

      let node = nodes[n];

      let compositeContextId = node.customProperties
                        ? node.customProperties.__composition_context_id : null;

      let clusterId = makeClusterId(node.descriptionLink, compositeContextId);

      let nodeGroup = nodesByClusterId[clusterId];
      if (!nodeGroup) {
        nodeGroup = [];
        nodesByClusterId[clusterId] = nodeGroup;
      }
      nodeGroup.push(node);
    }

    var items = [];
    //var clusters = [];
    for (let key in nodesByClusterId) {

      if (!nodesByClusterId.hasOwnProperty(key)) {
        continue;
      }

      let containerGroup = nodesByClusterId[key];
      if (key.endsWith('__discovered__')) { // we cannot restore cluster info in this case
        items = items.concat(containerGroup);
      } else {
        let clusterSize = containerGroup.length;
        let firstContainer = containerGroup[0];
        if (clusterSize > 1) { // cluster
          items.push(makeClusterObject(key, containerGroup));
        } else {
          items.push(firstContainer);
        }
      }
    }

    return items;
  },

  onModifyClusterSize: function(clusterId, totalClusterSize) {
    let descriptionLink = getDescriptionLinkFromClusterId(clusterId);
    let contextId = getContextIdFromClusterId(clusterId);

    services.modifyClusterSize(descriptionLink, contextId, totalClusterSize)
        .then((clusterSizeRequest) => {

      this.openToolbarItem(constants.CONTEXT_PANEL.REQUESTS, RequestsStore.getData());
      actions.RequestsActions.requestCreated(clusterSizeRequest);
    });
  },

  onScaleContainer: function(descriptionLink, contextId) {
    var clusterId = makeClusterId(descriptionLink, contextId);

    // also handles the case when the container is already in cluster
    services.loadClusterContainers(descriptionLink, contextId).then((containers) => {
      let clusterSize = getClusterSize(containers);

      return this.onModifyClusterSize(clusterId, clusterSize + 1);
    });
  },

  onOpenToolbarEventLogs: function(highlightedItemLink) {
    actions.EventLogActions.openEventLog(highlightedItemLink);
    this.openToolbarItem(constants.CONTEXT_PANEL.EVENTLOGS, EventLogStore.getData());
  },

  onOpenToolbarRequests: function() {
    actions.RequestsActions.openRequests();
    this.openToolbarItem(constants.CONTEXT_PANEL.REQUESTS, RequestsStore.getData());
  },

  onCloseToolbar: function() {
    this.closeToolbar();
  },

  onOpenShell: function(containerId) {
    services.getContainerShellUri(containerId).then((shellUri) => {
      var path = window.location.pathname || '';
      // Remove file endings, like index.html
      path = path.substring(0, path.lastIndexOf('/') + 1);
      if (shellUri.indexOf(path) === 0) {
        shellUri = shellUri.substring(path.length);
      }
      if (shellUri.charAt(0) === '/') {
        shellUri = shellUri.substring(1);
      }
      if (shellUri.charAt(shellUri.length - 1) !== '/') {
        shellUri += '/';
      }

      var selectedContainerDetails = getSelectedContainerDetailsCursor.call(this).get();

      if (!selectedContainerDetails || containerId !== selectedContainerDetails.documentId) {
        return;
      }

      updateSelectedContainerDetails.call(this, ['shell'], {
        shellUri: shellUri
      });
      this.emitChange();
    });
  },

  onCloseShell: function() {
    updateSelectedContainerDetails.call(this, ['shell'], null);
    this.emitChange();
  },

  getHostsForContainersCall(containers) {
    let hosts = utils.getIn(this.data, ['listView', 'hosts']) || {};
    let hostLinks = containers.filter((container) =>
      container.parentLink).map((container) => container.parentLink);
    let links = [...new Set(hostLinks)].filter((link) => !hosts.hasOwnProperty(link));
    if (links.length === 0) {
      return Promise.resolve(hosts);
    }
    return services.loadHostsByLinks(links).then((newHosts) => {
      this.setInData(['listView', 'hosts'], $.extend({}, hosts, newHosts));
      return utils.getIn(this.data, ['listView', 'hosts']);
    });
  },

  onListError: function(e) {
    let errorMessage = utils.getErrorMessage(e);

    this.setInData(['listView', 'error'], errorMessage);
    this.emitChange();
  },

  onGenericDetailsError: function(e) {
    let errorMessage = utils.getErrorMessage(e);

    this.setInData(['selectedItemDetails', 'error'], errorMessage);
    this.emitChange();
  },

  // Exposed only for testing, not to be used in the actual application
  _clearData: function() {
    if (!jasmine) { // eslint-disable-line
      throw new Error('_clearData is not supported');
    }

    this.data = Immutable({});
  }
});

export default ContainersStore;
