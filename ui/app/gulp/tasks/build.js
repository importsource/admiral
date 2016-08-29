/*
  The build task is responsible for compiling all the needed stuff and producing resources that when imported to a server can be run in a browser.
*/

var gulp = require('gulp');
gulp.task('build', ['eslint', 'process-sources', 'process-vendor-libs', 'html', 'styles', 'images', 'fonts', 'i18n', 'templates', 'templates-vue', 'copy-api-tests']);
