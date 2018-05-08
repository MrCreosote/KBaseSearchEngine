
KBaseSearchEngine
=================

Build status (master):
[![Build Status](https://travis-ci.org/kbase/KBaseSearchEngine.svg?branch=master)](https://travis-ci.org/kbase/KBaseSearchEngine) [![codecov](https://codecov.io/gh/kbase/KBaseSearchEngine/branch/master/graph/badge.svg)](https://codecov.io/gh/kbase/KBaseSearchEngine)

Powers KBase Search MKII.

Documentation
-------------

Formal documentation is available as a work in progress - [PDF](/docsource/KBaseSearchEngine.pdf), [HTML](/docsource/build/html/index.html).

HTML API documention is available [here](http://htmlpreview.github.io/?https://github.com/kbase/KBaseSearchEngine/blob/master/KBaseSearchEngine.html).

Developer Notes
---------------

### Adding and releasing code

* Adding code
  * All code additions and updates must be made as pull requests directed at the develop branch.
    * All tests must pass and all new code must be covered by tests.
    * All new code must be documented appropriately
      * Javadoc
      * General documentation if appropriate
      * Release notes
* Releases
  * The master branch is the stable branch. Releases are made from the develop branch to the master
    branch.
  * Update the version as per the semantic version rules in
    `/lib/src/kbasesearchengine/main/SearchVersion.java`.
  * Tag the version in git and github.

### Running tests

#### With the KBase SDK

The easier of the two way to run tests is to use the KBase SDK via `kb-sdk test`. Consult the
SDK documentation for instructions.

#### Without the SDK

The second option for running tests runs outside of an SDK docker container and thus requires
considerably more setup - MongoDB 2.6+ and ElasticSearch 5.5 must be installed. Consult their
respective documentation for instructions.

The tests use the same configuration file as that used by the KBase SDK.
The configuration file can be copied from another repo and adjusted appropriately or generated by
running `kb-sdk test`.

The following keys must be added to the test configuration file:

`test.mongo.exe` - the path to the mongo DB executable.  
`test.elasticsearch.exe` - the path to the ElasticSearch executable.  
`test.temp.dir` - a temporary directory for test files.  
`test.jars.dir` - the path to jars directory inside the KBase jars repo, e.g.
`[path to jars repo]/lib/jars.`

The following key is optional:

`test.temp.dir.keep` - `true` to keep test files after running tests, anything else to delete
them.

Once the requirements are installed and the test configuration file is ready, from the root of
the `KBaseSearchEngine` repo, run tests as follows:


	ant compile -Djars.dir=[path to jars repo]/lib/jars
	ant test-report -Djars.dir=[path to jars repo]/lib/jars -Dtest.cfg=[path to test configuration file]




