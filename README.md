# geneseer

A search-based program-repair tool for Java.

## Running

Start a JVM with geneseer and all its dependencies on the classpath. We recommend to simply use the
`jar-with-dependencies` of geneseer, then you only need to provide that single jar file. The main class is
`net.ssehub.program_repair.geneseer.Geneseer`.

The following command line arguments must be specified, each with a following value:

* `--project-directory`: The path to the root directory of the project to repair. Execution of the test suite will be
done with this as the working directory.
* `--source-directory`: The path to the directory that contains the source code files to repair. May be relative to the
project directory.
* `--test-classpath`: The classpath to execute the test suite of the project to repair. This should include the test
classes themselves, the required runtime dependencies (both for the program and for the test suite), but *not* the
compiled classes of the program under test. Note that Junit must not be specified, as the test driver of geneseer
already includes that. Entries may be relative to the project directory
* `--test-classes`: The fully qualified class names of the test classes in the test suite, joined with `:`.

The following optional command line arguments can be specified (also with a following value):

* `--compile-classpath`: The classpath to use when compiling the source code files. This should include all the
compile-time dependencies; usually this is a list of jars. Entries may be relative to the project directory.
* `--encoding`: The encoding of the source files of the project to repair. For example `ISO-8859-1`. If this is not
specified, the default encoding of the operating system is used.

The classpaths (`--test-classpath` and `--compile-classpath`) can be specified with the platform-specific file separator
character (`;` on Windows, `:` on Unix-likes). If `;` does not occur, then `:` may also be used on Windows.

To get prettier and filtered log output, add the following JVM arguments:

* `-Djava.util.logging.config.class=net.ssehub.program_repair.geneseer.logging.LoggingConfiguration`
* `-Djava.util.logging.config.file=logging.properties`

Note that these are *not* a command line arguments for this program, they need to be specified before the main class or
`-jar` parameter.

`logging.properties` should point to the file included in this repository; if it is not available in the working
directory where you launch this program, copy it there or specify the full path to it in the JVM argument.

As an example, a full execution of geneseer may look like this:
```
java -Djava.util.logging.config.class=net.ssehub.program_repair.geneseer.logging.LoggingConfiguration -Djava.util.logging.config.file=logging.properties -cp geneseer-jar-with-dependencies.jar net.ssehub.program_repair.geneseer.Geneseer --project-directory /path/to/project-to-fix --source-directory src/main/java --encoding ISO-8859-1 --compile-classpath lib/some-lib.jar:lib/other-lib.jar --test-classpath target/test-classes/:lib/some-lib.jar:lib/other-lib.jar:lib/test-lib.jar --test-classes com.example.TestClass1:com.example.TestClass2
```

### Environment

geneseer will use the `javac` command to compile the source code files of the project to repair, and the `java` command
to run the test suite of the project to repair. Make sure that the `PATH` environment variable is set up in a way that
the proper version of JDK is used. For example, for defects4j 2.0.0, this should be Java 1.8. geneseer itself requires
a JRE of version 17 or later, which of course conflicts with this environment specification. Thus you may need to
specify the full path to the JRE 17 `java` executable when launching genesser (e.g. on Ubuntu, this is
`/usr/lib/jvm/java-1.17.0-openjdk-amd64/bin/java`).

## Output

Geneseer outputs a single line to stdout, with semicolon-separated values. The first value specifies the type of result:

* `FOUND_FIX`: Found a full fix (i.e., all test cases succeed with the found patch).
* `GENERATION_LIMIT_REACHED`: The generation limit was reached, without a full fix being found (a variant with improved
fitness may have been found, but some test cases are still failing).
* `ORIGINAL_UNFIT`: The original source code could not be parsed or compiled.
* `IO_EXCEPTION`: An IOException occurred during the execution.
* `OUT_OF_MEMORY`: The JVM threw an OutOfMemoryError. No further values are present.
* `null`: An unexpected exception occurred during the execution. No further values are present.

Except for the last two types, the following values are (in this order):

2. The generation where geneseer stopped. `0` for `ORIGINAL_UNFIT`. May be blank for `IO_EXCEPTION`. For
`GENERATION_LIMIT_REACHED` this is always the generation limit.
3. The fitness of the original, unmodified variant (only for `FOUND_FIX` and `GENERATION_LIMIT_REACHED`).
4. The maximum fitness that can be achieved (only for `FOUND_FIX` and `GENERATION_LIMIT_REACHED`). A variant with this
fitness represents a full fix.
5. The best fitness seen in any variant (only for `FOUND_FIX` and `GENERATION_LIMIT_REACHED`). For `FOUND_FIX` this is
thus equal to the previous value (maximum fitness).
6. Only for `IO_EXCEPTION`: The exception message.

For example, the output may look like this:
```
GENERATION_LIMIT_REACHED;10;484.0;504.0;501.0;
```
Here, geneseer ran for 10 generations (2nd value), but could not find a full fix (1st value). The original code had a
fitness of 484 (3rd value). A full fix would have a fitness of 504 (4th value). The best variant that was found during
the 10 generations had a fitness of 501 (5th value); this is an improvement over the initial fitness, but not yet a full
fix that passes all test cases. 

Geneseer outputs logging information to stderr. This contains much more detailed information on the execution. For
example, it contains the best found variant and it's modifications compared to the original code. It may also hint at
problems in the setup (e.g. when classpath elements are specified that do not exist) and contains detailed timing
measurements of different areas in the execution.

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
