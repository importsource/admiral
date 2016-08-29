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

import RequestsListVue from 'RequestsListVue';
import RequestsListItemVue from 'RequestsListItemVue';
import Component from 'components/common/Component';
import InlineDeleteConfirmationTemplate from 'InlineDeleteConfirmationTemplate';
import { RequestsActions, AppActions } from 'actions/Actions';
import constants from 'core/constants';
import utils from 'core/utils';
import routes from 'core/routes';
import links from 'core/links';

var RequestsListVueComponent = Vue.extend({
  template: RequestsListVue,
  props: {
    model: {required: true}
  },
  components: {
    'request-item': {
      template: RequestsListItemVue,
      props: {
        model: {required: true}
      },
      computed: {
        progress: function() {
          return this.model.taskInfo.stage === 'FAILED' ? 100 : this.model.progress;
        }
      },
      data: function() {
        return {
          expanded: false
        };
      },
      attached: function() {
        // Handle Confirm Request Item Delete
        $(this.$el).on('click', '.delete-inline-item-confirmation-confirm', (e) => {
          e.preventDefault();

          // Hide cancel button and show loading indication
          var $deleteButton = $(e.currentTarget);
          $deleteButton.prev('.delete-inline-item-confirmation-cancel').addClass('hide');
          $deleteButton.css('float', 'right');
          $deleteButton.find('.fa').removeClass('hide');

          RequestsActions.removeRequest(this.model.documentSelfLink);
        });

        // Handle Cancel Request Item Delete
        $(this.$el).on('click', '.delete-inline-item-confirmation-cancel', (e) => {
          e.preventDefault();
          this.hideDeleteConfirmationButtons(e);
        });
      },
      methods: {
        isRequestRunning: function(item) {
          return utils.isRequestRunning(item);
        },

        isRequestFailed: function(item) {
          return utils.isRequestFailed(item);
        },

        getResourceIds: function(item) {
          var ids = [];
          if (item && item.resourceLinks) {
             for (var i = 0; i < item.resourceLinks.length; i++) {
              var link = item.resourceLinks[i];
              if (link) {
                ids.push(utils.getDocumentId(link));
              }
            }
          }
          return ids;
        },

        isRequestFinished: function(item) {
          return utils.isRequestFinished(item);
        },

        isEnabled(item) {
          return this.isRequestFinished(item) ||
            (this.isRequestFailed(item) && item.eventLogLink);
        },

        isDeleteEnabled(item) {
          return !this.isRequestRunning(item);
        },

        toggleErrorMessage($event) {
          $event.preventDefault();

          this.expanded = !this.expanded;
        },

        getProgressClass: function(item) {
          if (utils.isRequestFailed(item)) {
            return ['progress-bar-danger'];
          } else if (utils.isRequestRunning(item)) {
            return ['progress-bar-info', 'progress-bar-striped', 'active'];
          } else {
            return ['progress-bar-info'];
          }
        },

        redirect($e) {
          $e.preventDefault();

          if (this.isRequestFinished(this.model)) {
            hasher.setHash(this.getRequestResourceHref(this.model));
            return;
          }

          if (this.isRequestFailed(this.model) && this.model.eventLogLink) {
            AppActions.openToolbarEventLogs(this.model.eventLogLink);
            return;
          }
        },

        getRequestResourceHref: function(item) {
          var params = {};

          if (item.resourceLinks) {
            params[constants.SEARCH_OCCURRENCE.PARAM] = constants.SEARCH_OCCURRENCE.ANY;
            for (var i = 0; i < item.resourceLinks.length; i++) {
              var link = item.resourceLinks[i];
              if (link) {
                params.documentId = utils.getDocumentId(link);
                if (link.indexOf(links.COMPOSITE_COMPONENTS) !== -1) {
                  params[constants.SEARCH_CATEGORY_PARAM] =
                      constants.CONTAINERS.SEARCH_CATEGORY.APPLICATIONS;
                } else if (link.indexOf(links.CONTAINERS) !== -1) {
                  params[constants.SEARCH_CATEGORY_PARAM] =
                      constants.CONTAINERS.SEARCH_CATEGORY.CONTAINERS;
                }
              }
            }
          }

          return '#/' + routes.getContainersHash(params);
        },

        deleteRequest: function($event) {
          $event.stopPropagation();
          $event.preventDefault();

          let $requestItemHolder = $(this.$el);
          let $requestItem = $requestItemHolder.children('.request-item');

          let $deleteConfirmationHolder = $(InlineDeleteConfirmationTemplate());
          $deleteConfirmationHolder.height($requestItem.outerHeight(true));

          let $actions = $requestItemHolder.children('.request-status-actions');
          $actions.addClass('hide');
          $deleteConfirmationHolder.insertAfter($actions);

          let $deleteConfirmation = $deleteConfirmationHolder
            .find('.delete-inline-item-confirmation');
          utils.slideToLeft($deleteConfirmation);
        },

        hideDeleteConfirmationButtons: function($event) {
          $event.stopPropagation();
          $event.preventDefault();

          let $deleteConfirmationHolder = $($event.currentTarget)
            .closest('.delete-inline-item-confirmation-holder');

          utils.fadeOut($deleteConfirmationHolder, function() {
            $deleteConfirmationHolder.remove();
          });

          $(this.$el).children('.request-status-actions').removeClass('hide');
        }
      }
    }
  },

  methods: {
    detached: function() {
      RequestsActions.closeRequests();
    },

    requestRunningFilter: function(item) {
      return utils.isRequestRunning(item);
    },

    requestFailedFilter: function(item) {
      return utils.isRequestFailed(item);
    },

    selectTab($event, itemsType) {
      $event.stopPropagation();
      $event.preventDefault();
      $($event.target).tab('show');
      RequestsActions.selectRequests(itemsType);
    },

    clear: function() {
      RequestsActions.clearRequests();
    },

    loadMore: function(itemsType) {
      if (this.model.nextPageLink && itemsType === this.model.itemsType) {
        RequestsActions.openRequestsNext(this.model.nextPageLink);
      }
    }
  }
});

Vue.component('requests-list', RequestsListVueComponent);

class RequestsList extends Component {
  constructor() {
    super();

    this.$el = $('<div><requests-list v-bind:model="model"></requests-list></div>');

    this.vue = new Vue({
      el: this.$el[0],
      data: {
        model: {}
      }
    });
  }

  setData(data) {
    Vue.set(this.vue, 'model', data);
  }

  getEl() {
    return this.$el;
  }
}

export default RequestsList;
