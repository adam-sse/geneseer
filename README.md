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
* `--config`: A path to the configuration file to use. See below.

The classpaths (`--test-classpath` and `--compile-classpath`) can be specified with the platform-specific file separator
character (`;` on Windows, `:` on Unix-likes). If `;` does not occur, then `:` may also be used on Windows.

By default, the CONFIG level and above is logged. To change this, add the this JVM argument:
`-Djava.util.logging.config.file=logging.properties`. Note that this is *not* a command line argument for this program,
it needs to be specified before the main class or `-jar` parameter. `logging.properties` should point to the
configuration file for the Java logging facility (e.g. see the file included in this repository); if it is not available
in the working directory where you launch this program, copy it there or specify the full path to it in the JVM argument.

As an example, a full execution of geneseer may look like this:
```
java -Djava.util.logging.config.file=logging.properties -cp geneseer-jar-with-dependencies.jar net.ssehub.program_repair.geneseer.Geneseer --project-directory /path/to/project-to-fix --source-directory src/main/java --encoding ISO-8859-1 --compile-classpath lib/some-lib.jar:lib/other-lib.jar --test-classpath target/test-classes/:lib/some-lib.jar:lib/other-lib.jar:lib/test-lib.jar --test-classes com.example.TestClass1:com.example.TestClass2
```

### Orchestrator

The main class `net.ssehub.program_repair.geneseer.orchestrator.Orchestrator` can be used to run geneseer automatically
on all (or a subset of) [defects4j](https://github.com/rjust/defects4j) bugs. It will check out, compile, and prepare
the defects4j bugs in the current working directory (sub-directories for each project, and then sub-sub directories for
each bug).

The following command line arguments are mandatory (each with a following value):

* `--jvm`: The JVM binary to run the geneseer processes with. This may absolute or on the path.
* `--geneseer-jar`: The path to the jar-with-dependencies of geneseer.
* `--defects4j`: The path to the defects4j root directory. defects4j must be properly set up there (check that
`framework/bin/defects4j` exists in it and is executable), but does not need to be on the path.

The following optional command line arguments can be specified (each with a following value):

* `--target`: Select which main program should be run on the projects. Either of the following values:
    * `GENESEER` (the default) will run normal geneseer on each bug.
    * `SETUP_TEST` will only parse, compile, and evaluate (run tests) on the projects, to check if everything is set up
properly.
    * `ONLY_DELETE` will go through each suspicious statement one by one and try to delete it individually.
* `--threads`: How many instances of genseer to run in parallel. Default is 1.
* `--config`: A path to the configuration file to use. See below. This is passed to each geneseer execution.

After the command line arguments, you can specify on which defects4j bugs geneseer should be run. Specify nothing to run
on all bugs in all projects. Specify `<project name>/*` (be careful that your shell doesn't expand the `*` here!) to run
on all bugs of the given project (e.g. `Closure/*`). Specify `<project name>/<bug number>` to run on a single bug (e.g.
`Closure/1`). The latter two can be given multiple times, in which case geneseer is run on all of the specified bugs.  

For example, an execution of the orchestrator may look like this:

```
java -Djava.util.logging.config.file=logging.properties -cp geneseer-jar-with-dependencies.jar net.ssehub.program_repair.geneseer.orchestrator.Orchestrator --jvm java --geneseer-jar geneseer-jar-with-dependencies.jar --defects4j ../defects4j --config geneseer.properties --threads 8
```

Note that defects4j typically requires a specific Java version on the path (for defects4j 2.0.0, this is Java 1.8). Due
to this, make sure that the `PATH` environment variable is set up in a way that the proper version of JDK is used.
geneseer (and the orchestrator) itself requires a JRE of version 17 or later, which of course conflicts with the
environment requirements of defects4j. Thus you may need to specify the full path to the JRE 17 `java` executable when
launching the orchestrator and also pass it via the `--jvm` argument (e.g. on Ubuntu, this is
`/usr/lib/jvm/java-1.17.0-openjdk-amd64/bin/java`).

### Configuration file

The optional command line argument `--config` can specify the path to a properties file that contains configuration
options for geneseer. The prefix `setup.` refers to configuration options regarding compiling, running tests, etc. The
prefix `genetic.` refers to configurations options of the genetic algorithm. Here is a list of possible keys, their
meaning, and the default values:

| Key                              |  Default Value       | Description                                                |
|----------------------------------|----------------------|------------------------------------------------------------|
| `setup.jvmBinaryPath`            | `java`               | The path to the JVM binary to run tests. May be absolute or on the path. |
| `setup.javaCompilerBinaryPath`   | `javac`              | The path to the Java compiler to compile the project. May be absolute or on the path. |
| `setup.testExecutionTimeoutMs`   | `120000` (2 minutes) | The amount of milliseconds before considering a test execution timed-out. The test process will be killed and the tests count as failures. |
| `setup.coverageMatrixSimplified` | `true`               | Whether to aggregate the coverage per-class instead of running each test method individually when measuring the suspiciousness. If this is `true`, then the execution is (much) faster, but the suspiciousness values will be less accurate. |
| `setup.debugTestDriver`          | `false`              | Whether to print debug output of the test driver process to stderr. |
| `setup.testsToRun`               | `ALL_TESTS`          | Relevant only for the orchestrator: Whether to run all tests or only the tests that defects4j marked as relevant. Possible values are `ALL_TESTS` and `RELEVANT_TESTS`. |
| `genetic.randomSeed`             | `0`                  | The seed to initialize the random source with. If this is the same (and the test cases are deterministic), then the same result is produced. |
| `genetic.populationSize`         | `40`                 | The number of variants in a generation. |
| `genetic.generationLimit`        | `10`                 | The maximum number of generations to run for (inclusive). |
| `genetic.negativeTestsWeight`    | `10`                 | The fitness function weight of test cases that are negative for the unmodified, original code. |
| `genetic.positiveTestsWeight`    | `1`                  | The fitness function weight of test cases that are positive for the unmodified, original code. |
| `genetic.mutationProbability`    | `4`                  | Controls the probability that mutations are created in a variant. This value is divided by the number of suspicious statements to get the probability of introducing a mutation at a suspicious statement. |
| `genetic.statementScope`         | `GLOBAL`             | Defines where other statements for mutations are taken from. Either `GLOBAL` or `FILE`. |

## Output

Geneseer outputs a JSON object to stdout. It has the following structure (no order guaranteed, elements are only present
if applicable):

```json
{
  "result": "FOUND_FIX or GENERATION_LIMIT_REACHED or ORIGINAL_UNFIT or IO_EXCEPTION or OUT_OF_MEMORY",
  "generation": 1, // the generation that was reached; not present e.g. when ORIGINAL_UNFIT
  "fitness": {
    "original": 109.0, // the fitness of the original code
    "max": 129.0, // the maximum achievable fitness (this would constitute a full fix)
    "best": 119.0 // the fitness of the best variant
  },
  "patch": {
    "diff": "the diff as a output by `git diff` for the best variant", // empty string if unmodified variant is best
    "addedLines": 5, // the number of line additions in the diff
    "removedLines": 3 // the of line removals in the diff
  },
  "ast": {
    "nodes": 123, // total nodes in AST
    "suspicious": 66 // number of statements with any suspiciousness
  },
  "llmCalls": 0, // number of calls to an LLM to create mutations
  "timings": { // timing measurements in ms
    "code-writing": 99,
    "compilation": 31651
    // ...
  },
  "exception": "exception message" // only present for IO_EXCEPTION
}
```
The meaning of the `result` types is:
* `FOUND_FIX`: Found a full fix (i.e., all test cases succeed with the found patch).
* `GENERATION_LIMIT_REACHED`: The generation limit was reached, without a full fix being found (a
variant with improved fitness may have been found, but some test cases are still failing).
* `ORIGINAL_UNFIT`: The original source code could not be parsed or compiled.
* `IO_EXCEPTION`: An IOException occurred during the execution.
* `OUT_OF_MEMORY`: The JVM threw an OutOfMemoryError. No further values are present.

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
