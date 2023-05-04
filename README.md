# geneseer

A program-repair tool for Java.

## Compiling

This project uses [Maven](https://maven.apache.org/) for dependency management and the build process. To simply build
jars, run:
```
mvn package
```

This creates two jar files in the `target` folder (`$version` is the version that was built, e.g. `1.0.0`
or `1.0.1-SNAPSHOT`):

* `geneseer-$version.jar` just includes the class files of this program.
* `geneseer-$version-jar-with-dependencies.jar` includes the class files of this program, plus all
dependencies. This means that this jar can be used when you don't want to manually provide all dependencies of this
program each time you execute it.

This project has `geneseer-test-driver` as a dependency in Maven. This is not available in the default Maven
repositories, so you need to install it to your local Maven repository. See
[the README of geneseer-test-driver](https://github.com/adam-sse/geneseer-test-driver#compiling).
