# geneseer

A search-based program-repair tool for Java.

## Running

Start a JVM with geneseer and all its dependencies on the classpath. We recommend simply using the
`jar-with-dependencies` of geneseer, then you only need to provide that single jar file. The main class is
`net.ssehub.program_repair.geneseer.Geneseer`; it is also set as the main class in the jar, so `-jar` can be used to
start it.

The following command line arguments must be specified, each followed by a value:

* `--project-directory`: The path to the root directory of the project to repair. The test suite will be executed with
this as the working directory.
* `--source-directory`: The path to the directory that contains the source code files to repair. May be relative to the
project directory.
* `--test-classpath`: The classpath to execute the test suite of the project to repair. This should include the test
classes themselves, the required runtime dependencies (both for the program and for the test suite), but *not* the
compiled classes of the program under test. Note that JUnit must not be specified, as it is already included in
geneseer's test driver. Each entry may be absolute or relative to the project directory.
* `--test-classes`: The fully qualified class names of the test classes in the test suite, joined with `:`.

The following optional command line arguments can be specified (also with a following value):

* `--compile-classpath`: The classpath to use when compiling the source code files. This should include all the
compile-time dependencies; usually this is a list of jars. Each entry may be absolute or relative to the project
directory.
* `--encoding`: The encoding of the source files of the project to repair. For example `ISO-8859-1`. If this is not
specified, the default encoding of the operating system is used.
* `--additional-javac-options`: A comma-separated list of additional command line options to pass to the Java compiler.
* `--config.*`: Configuration options start with `--config.`, see below.

The classpaths (`--test-classpath` and `--compile-classpath`) can be specified with the platform-specific file separator
character (`;` on Windows, `:` on Unix-like systems). If `;` does not occur, then `:` may also be used on Windows.

By default, messages in the `CONFIG` level and above are logged. To change this, add this JVM argument:
`-Dgeneseer.logLevel=<level>` (`<level>` can be one of `OFF`, `SEVERE`, `WARNING`, `INFO`, `CONFIG`, `FINE`, `FINER`,
`FINEST`, `ALL`). Note that this is *not* a command line argument for this program, it needs to be specified before the
main class or `-jar` parameter.

As an example, a full execution of geneseer may look like this:
```sh
java -Dgeneseer.logLevel=FINE -jar geneseer-jar-with-dependencies.jar \
    --project-directory /path/to/project-to-fix \
    --source-directory src/main/java \
    --encoding ISO-8859-1 \
    --compile-classpath lib/some-lib.jar:lib/other-lib.jar \
    --test-classpath target/test-classes/:lib/some-lib.jar:lib/other-lib.jar:lib/test-lib.jar \
    --test-classes com.example.TestClass1:com.example.TestClass2
```

### Defects4J

The main class `net.ssehub.program_repair.geneseer.defects4j.Defects4jRunner` can be used to run geneseer on a
bug from the [Defects4J](https://github.com/rjust/defects4j) database. It will check out, compile, and prepare
the Defects4J bug in the current working directory (a sub-directory for the project, and then a nested subdirectory for
the bug). It then proceeds with a normal execution of geneseer, with the required parameters for the Defects4J bug.
After the Defects4J setup, the output is identical to a normal geneseer execution.

The following command line argument is mandatory (with a following value):

* `--defects4j`: The path to the Defects4J root directory. Defects4J must be properly set up there (check that
`framework/bin/defects4j` exists in it and is executable), but does not need to be on the path.

The following optional command line arguments can be specified (each with a following value):

* `--config.*`: All configuration options are passed to the geneseer execution, see below.

After the command line arguments, you must specify on which Defects4J bug geneseer should be run. This is in the format
`<project name>/<bug number>` (e.g. `Closure/1`).  

For example, an execution of the Defects4J runner may look like this:

```sh
java -cp geneseer-jar-with-dependencies.jar net.ssehub.program_repair.geneseer.defects4j.Defects4jRunner \
    --defects4j ../defects4j \
    --config.genetic.llmMutationProbability 0 Cli/11
```

Note that Defects4J typically requires a specific Java version on the path (for Defects4J 2.0.0, this is Java 1.8). Due
to this, make sure that the `PATH` environment variable is set up so that the proper version of JDK is used. Geneseer
itself requires a JRE of version 17 or later, which of course conflicts with the environment requirements of Defects4J.
Thus you may need to specify the full path to the JRE 17 `java` executable when launching the Defects4J runner (e.g. on
Ubuntu, this is `/usr/lib/jvm/java-17-openjdk-amd64/bin/java`).

### Configuration

Command line arguments starting with `--config.` can specify configuration options for geneseer. The section `setup`
refers to configuration options regarding compiling, running tests, etc. The section `genetic` refers to configuration
options of the genetic algorithm. The section `llm` refers to configuration options for calling an LLM via a REST API.
The section `rag` refers to configuration options for calling an embedding API; these options are only required when
using RAG.
Here is a list of possible keys, their meaning, and the default values:

| Key                                       |  Default Value         | Description                                                |
|-------------------------------------------|------------------------|------------------------------------------------------------|
| `--config.setup.fixer`                    | `GENESEER`             | The fixer to use. Possible values: `GENESEER`, `LLM_SINGLE`, `SETUP_TEST`, `ONLY_DELETE`, `LLM_QUERY_ANALYSIS` |
| `--config.setup.jvmBinaryPath`            | `java`                 | The path to the JVM binary to run tests. May be absolute or on the path. |
| `--config.setup.javaCompilerBinaryPath`   | `javac`                | The path to the Java compiler to compile the project. May be absolute or on the path. |
| `--config.setup.testExecutionTimeoutMs`   | `120000` (2 minutes)   | The number of milliseconds before a test execution is considered timed out. The test process will be killed and the tests will count as failures. |
| `--config.setup.coverageMatrixSimplified` | `true`                 | Whether to aggregate the coverage per-class instead of running each test method individually when measuring the suspiciousness. If this is `true`, then the execution is (much) faster, but the suspiciousness values will be less accurate. |
| `--config.setup.suspiciousnessThreshold`  | `0.01`                 | The minimum suspiciousness value required; statements that are less suspicious will be ignored. |
| `--config.setup.testsToRun`               | `ALL_TESTS`            | Relevant only for the Defects4J runner: Whether to run all tests or only the tests that Defects4J marked as relevant. Possible values are `ALL_TESTS` and `RELEVANT_TESTS`. |
| `--config.setup.debugTestDriver`          | `false`                | Whether to print debug output of the test driver process to stderr. |
| `--config.genetic.randomSeed`             | `0`                    | The seed to initialize the random source with. If this is the same (and the test cases are deterministic), then the same result is produced. |
| `--config.genetic.populationSize`         | `40`                   | The number of variants per generation. |
| `--config.genetic.generationLimit`        | `10`                   | The maximum number of generations to run for (inclusive). |
| `--config.genetic.negativeTestsWeight`    | `10.0`                 | The fitness function weight of test cases that are negative for the unmodified, original code. |
| `--config.genetic.positiveTestsWeight`    | `1.0`                  | The fitness function weight of test cases that are positive for the unmodified, original code. |
| `--config.genetic.mutationProbability`    | `0.5`                  | The probability for each variant, that it is mutated at the end of a generation. |
| `--config.genetic.llmMutationProbability` | `0.0`                  | The probability that mutations are created by calling an LLM instead of the classic simple mutation operations (insert, swap, delete). |
| `--config.genetic.statementScope`         | `GLOBAL`               | Defines where other statements for mutations are taken from. Either `GLOBAL` or `FILE`. |
| `--config.llm.model`                      | `dummy`                | The name of the model to call. The special value `dummy` will not call an API but instead return a static dummy string (for debugging). |
| `--config.llm.api`                        | not set                | The API endpoint of the LLM. Consists of the provider type and the full endpoint URL, separated by `+` (e.g. `ollama+http://localhost:11434/api/chat`). Valid providers are `ollama` and `openai`. The URL should end with `/api/chat` for Ollama and `/v1/chat/completions` for OpenAI. |
| `--config.llm.apiToken`                   | not set                | If set, this is added as a `Bearer` token in the `Authorization` header for API calls. |
| `--config.llm.timeoutMs`                  | `1800000` (30 minutes) | The number of milliseconds before a query to the LLM is considered timed out. |
| `--config.llm.think`                      | not set                | The thinking/reasoning level to pass to the model. `true` or `false` for most models, `none`, `minimal`, `low`, `medium`, `high`, or `xhigh` for others. |
| `--config.llm.thinkingDelimiter`          | not set                | If set, everything up to the last occurrence of this sequence in the model output will be considered a thinking/reasoning trace and discarded (for instances where the API does not differentiate between thinking and answer). |
| `--config.llm.temperature`                | not set                | If set, this defines the temperature of the model. |
| `--config.llm.contextSize`                | not set                | Only for Ollama: the context window size of the model (in tokens). |
| `--config.llm.seed`                       | not set                | Only for Ollama: if set, this defines the seed to use for calls to the model. |
| `--config.llm.codeContextSelection`       | `SUSPICIOUSNESS`       | The method by which code snippets are ranked for inclusion in the prompt. Possible values: `SUSPICIOUSNESS` and `RAG`. |
| `--config.llm.maxCodeContext`             | `100`                  | The maximum number of lines of code to supply as code context in a query to the LLM. This does not include test code. |
| `--config.llm.projectOutline`             | `PARTIAL`              | The type of project outline to add to the prompt. Possible values: `FULL`, `PARTIAL`, `NONE` |
| `--config.rag.chromadbWorkerPythonBinaryPath`| not set             | Path to the `python` binary with necessary `chromadb` and `ollama` dependencies installed, see [Setup for RAG](#setup-for-rag) below. |
| `--config.rag.model`                      | not set                | The name of the model to use for embedding. |
| `--config.rag.api`                        | not set                | The Ollama API host (e.g. `http://localhost:11434`) used for embedding. |
| `--config.rag.persist`                    | `false`                | Whether to store and/or use the RAG database in the project directory. |

### Setup for RAG

When using RAG, there needs to be a Python environment with the `chromadb` and `ollama` dependencies installed. Using a
virtual environment like this is probably the easiest setup:

1. Create a venv somewhere (we'll call this `$chromadbPythonDir`): `cd $chromadbPythonDir && python -m venv .venv`
2. Activate the venv for the current shell: `source $chromadbPythonDir/.venv/bin/activate`
3. Install dependencies: `pip install --upgrade pip && pip install chromadb ollama`
4. Set the following option when running geneseer:
    * `--config.rag.chromadbWorkerPythonBinaryPath $chromadbPythonDir/.venv/bin/python`

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
    "mutations": [
      "textual description of first mutation",
      "textual description of second mutation"
      // ...
    ],
    "diff": "the diff as produced by `git diff` for the best variant", // empty string if unmodified variant is best
    "addedLines": 5, // the number of line additions in the diff
    "removedLines": 3 // the number of line removals in the diff
  },
  "ast": {
    "nodes": 123, // total nodes in AST
    "suspicious": 66 // number of statements with any suspiciousness
  },
  "evaluations": {
    "compilations": 23,
    "testSuiteRuns": 16
  },
  "mutationStats": {
    "insertions": 40,
    "deletions": 45,
    "failedMutations": 0,
    "successfulCrossovers": 3,
    "failedCrossovers": 7,
    "llmCallsOnUnmodified": 0,
    "llmCallsOnMutated": 0,
    "unusableLlmAnswers": 0
  },
  "llmStats": {
    "calls": 0,
    "answers": 0,
    "timeouts": 0,
    "totalQueryTokens": 0,
    "totalAnswerTokens": 0
  },
  "timings": { // timing measurements in ms
    "total": 4679, // (almost) complete runtime of geneseer, including initial evaluation of unmodified variant
    "genetic-algorithm": 1867, // runtime of genetic algorithm, after unmodified variant is evaluated
    "compilation": 2284, // total time spent compiling
    "fault-localization": 1757, // total time spent for fault localization (usually just once initially)
    "junit-evaluation": 257, // total time spent running tests (excluding coverage for fault-localization)
    "llm-query": 0 // total time spent querying the LLM for patches
    // ...
  },
  "logLines": { // number of log lines per level
    "SEVERE": 0,
    "WARNING": 6,
    "INFO": 57,
    "CONFIG": 34,
    "FINE": 0,
    "FINER": 0,
    "FINEST": 0
  },
  "exception": "exception message" // only present when applicable
}
```
The meaning of the `result` types is:
* `FOUND_FIX`: Found a full fix (i.e., all test cases succeed with the found patch).
* `GENERATION_LIMIT_REACHED`: The generation limit was reached, without a full fix being found (a
variant with improved fitness may have been found, but some test cases are still failing).
* `ORIGINAL_UNFIT`: The original source code could not be parsed or compiled.
* `IO_EXCEPTION`: An IOException occurred during the execution.
* `OUT_OF_MEMORY`: The JVM threw an OutOfMemoryError. No further values are present.

Geneseer outputs logging information to stderr. This contains much more detailed information on the execution. It may
also hint at problems in the setup (e.g. when classpath elements are specified that do not exist).

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
