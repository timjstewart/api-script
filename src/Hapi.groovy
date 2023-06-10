import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

import groovy.json.JsonSlurper
import groovy.transform.TypeChecked

import org.fusesource.jansi.Ansi.Color
import org.fusesource.jansi.AnsiConsole
import static org.fusesource.jansi.Ansi.*
import static org.fusesource.jansi.Ansi.Color.*

import static Utilities.*
import static Json.*

/**
 * Supported HTTP Methods
 */
enum Method {
    HEAD,
    GET,
    DELETE,
    POST,
    PUT,
    PATCH,
    OPTIONS
}

@TypeChecked
class Hapi {
    /**
     * Defines a script block that contains configuration, requests, and groups
     * of requests.
     */
    static void script(@DelegatesTo(Script) Closure<Void> c) {
        Terminal.init()

        final var args = getArguments()
        final var script = new Script()

        args.drop(1).each {
            var tokens = it.tokenize("=")
            if (tokens.size() == 2) {
                script.defineVariable(
                    tokens[0].trim(), tokens[1].trim())
            }
        }

        c.delegate = script
        c.resolveStrategy = Closure.DELEGATE_ONLY
        c.call()

        if (args.length == 0) {
            script.printAvailableCommands()
        } else {
            var commandName = args[0]
            if (commandName in script.commands) {
                script.runCommand(commandName)
            } else {
                println("Unknown command '${commandName}'")
                script.printAvailableCommands()
            }
        }

        Terminal.close()
    }

    private static String[] getArguments() {
        ProcessHandle.current().info().arguments()
            .orElse([] as String[]).toList()
            .dropWhile{!it.endsWith(".groovy")}
            .drop(1)
    }
}

@TypeChecked
class Terminal {
    static void init() {
        AnsiConsole.systemInstall()
        print(ansi().a(Attribute.RESET))
    }

    static void close() {
        AnsiConsole.systemUninstall()
        print(ansi().a(Attribute.RESET))
    }
}

@TypeChecked
abstract class ICommand {
    String name

    abstract List<RequestDSL> getRequests()

    void checkDependencies() {
        var providedValues = new HashSet<String>()

        // Check each Request's individual dependencies.
        getRequests().each {
            it.checkDependencies()
        }

        // Check dependencies that flow from one Request to the next.
        getRequests().collate(2, 1).each {
            var providingRequest = it[0]
            var requiringRequest = it[1]

            // no dependency to check
            if (!requiringRequest)
                return

            providedValues.addAll(
                providingRequest.providers.keySet()
            )

            if (requiringRequest.dependency) {
                providedValues.addAll(
                    requiringRequest.dependency.dependsOn.request.providers.keySet()
                )
            }

            requiringRequest.valueReferences().each {
                if (!(it in providedValues)) {
                    throw new HapiException(
                        "'${it}' was not found in provided values: ${providedValues.join(', ')}")
                }
            }
        }
    }
}

@TypeChecked
class Command extends ICommand {
    final String name
    private RequestDSL request

    Command(String name, RequestDSL request = null) {
        this.name = name
        this.request = request
    }

    @Override
    List<RequestDSL> getRequests() {
        request ? [request] : []
    }

    def setRequest(RequestDSL request) {
        this.request = request
    }

    @Override
    String toString() {
        "Command: ${name} hasRequest: ${request != null}"
    }
}

@TypeChecked
class CommandGroup extends ICommand {
    final String name
    private List<Command> commands = []

    CommandGroup(String name, List<Command> commands = []) {
        this.name = name
        this.commands = commands
    }

    @Override
    List<RequestDSL> getRequests() {
        commands.collectMany {it.getRequests()}
    }

    @Override
    String toString() {
        "CommandGroup: ${name} requests: ${commands.collect{it.name}}"
    }
}

@TypeChecked
class Script implements HasStyle {
    private Config _config = new Config()
    private Map<String, ICommand> commands = [:]
    private Map<String, String> variables = [:]

    // Aesthetic wrappers over `Script.request()`.
    RequestDSL DELETE(Command command, String url, Closure<RequestDSL> c = null) {return request(command, Method.DELETE, url, c)}
    RequestDSL GET(Command command, String url, Closure<RequestDSL> c = null) {return request(command, Method.GET, url, c)}
    RequestDSL HEAD(Command command, String url, Closure<RequestDSL> c = null) {return request(command, Method.HEAD, url, c)}
    RequestDSL OPTIONS(Command command, String url, Closure<RequestDSL> c = null) {return request(command, Method.OPTIONS, url, c)}
    RequestDSL PATCH(Command command, String url, Closure<RequestDSL> c = null) {return request(command, Method.PATCH, url, c)}
    RequestDSL POST(Command command, String url, Closure<RequestDSL> c = null) {return request(command, Method.POST, url, c)}
    RequestDSL PUT(Command command, String url, Closure<RequestDSL> c = null) {return request(command, Method.PUT, url, c)}

    void printAvailableCommands() {
        if (commands.isEmpty()) {
            println("No commands defined in script.  See README.md for how to define commands.")
        } else {
            println("Available commands:")
            commands.keySet().sort().each {
                println("  ${it}")
            }
        }
    }

    void defineVariable(String name, String value) {
        variables[name] = value
    }

    String env(String envVarName, String defaultValue = null) {
        if (variables.containsKey(envVarName)) {
            variables[envVarName]
        } else {
            try {
                Utilities.getEnvVar(envVarName, defaultValue)
            } catch (HapiException ex) {
                Utilities.fatalError(ex.getMessage())
            }
        }
    }

    Script group(String name, List<Command> commands) {
        this.commands[name] = new CommandGroup(name, commands)
        this
    }

    void runCommand(String commandName) {
        if (commandName in commands) {
            runCommand(commands[commandName])
        } else {
            Utilities.fatalError("Could not run '${commandName}'.")
        }
    }

    void runCommand(ICommand command) {
        try {
            command.checkDependencies()

            final var dictionary = new Dictionary()

            command.requests.each {
                if (it.dependency) {
                    ensureDependency(it.dependency, dictionary)
                }

                final Response response = it.sendRequest(dictionary)
                if (_config.logResponseBody()) {
                    logResponse(command, response)
                }
                dictionary.addSource(new DictionarySource(it, response))
                println()
            }
        } catch (HapiException ex) {
            Utilities.fatalError(ex.getMessage())
        }
    }

    private static void logResponse(final ICommand command, final Response response) {
        final var logDirectory = new File("hapi-logs")
        logDirectory.mkdir()

        final Date date = new Date()
        final String stamp = date.format("YYYYMMdd-HHmmssMs")

        final var logFile = new File(logDirectory, "${command.name}-${stamp}.json")
        logFile << response.body
    }

    private ensureDependency(Dependency dependency, Dictionary dictionary) {
        final String valueName = dependency.valueName
        final Command command = dependency.dependsOn
        if (command) {
            final RequestDSL request = command.request
            if (request.dependency) {
                ensureDependency(request.dependency, dictionary)
            }
            if (!dictionary.hasValue(valueName)) {
                final Response valueResponse = request.sendRequest(dictionary)
                dictionary.addSource(
                    new DictionarySource(request, valueResponse))
                if(!dictionary.hasValue(valueName)) {
                    throw new EvaluationError(
                        "Could not acquire value named '${valueName}' from " +
                            "dependency request '${request.url}'.")
                }
            }
        }
    }

    Script config(@DelegatesTo(Config) Closure<Void> c) {
        c.delegate = _config
        c.resolveStrategy = Closure.DELEGATE_ONLY
        c.call()
        this
    }

    private RequestDSL request(
        Command command,
        Method method,
        String url,
        @DelegatesTo(RequestDSL) Closure<RequestDSL> c) {

        final var dsl = new RequestDSL(this, _config, method, url)

        command.setRequest(dsl)
        commands[command.name] = command

        try {
            if (c) {
                c.delegate = dsl
                c.resolveStrategy = Closure.DELEGATE_ONLY
                c.call()
            }
            return dsl
        } catch (SyntaxError | EvaluationError ex) {
            Utilities.fatalError(ex.getMessage())
        }
    }

    /**
     * If a command by the specified name already exists, return it.
     * Otherwise create a new Command.
     *
     * TODO: When we create a new Command, should we add it to the list
     *       of commands?
     */
    ICommand propertyMissing(String name) {
        if (name in commands) {
            // Called when defining a Group and a command is referenced.
            commands[name]
        } else {
            // Called when defining a Request.
            new Command(name)
        }
    }

    @Override
    String toString() {
        var sb = new StringBuffer()
        commands.each {
            println("Command: ${it}")
        }
        sb.toString()
    }
}

@TypeChecked
class RequestDSL implements HasStyle {
    private static HttpClient httpClient = HttpClient.newBuilder().build()

    private final Method method
    private final String url
    private final List<Tuple2<String, String>> params = new ArrayList<>()
    private final Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER)
    private String body

    private final Map<String, Provider> providers = [:]
    private final Config config

    // Currently, each request can only have one dependency.
    private Dependency dependency
    private Script script

    RequestDSL(Script script, Config config, Method method, String url) {
        this.script = script
        this.config = config
        this.method = method
        this.url = url
    }

    HeaderDSL header(String name) {
        new HeaderDSL(this, name)
    }

    ParamDSL param(String name) {
        new ParamDSL(this, name)
    }

    void body(String body) {
        this.body = body
    }

    List<String> provides() {
        providers.keySet().toList()
    }

    Provider getProvider(String valueName) {
        providers[valueName]
    }

    String describeProviders() {
        "${url} - ${providers.keySet()}"
    }

    Dependency requires(Command request) {
        Dependency source = new Dependency(request)
        this.dependency = source
        source
    }

    private HttpRequest buildRequest(Dictionary dictionary) {
        var interpolatedParams = params.collect {
            new Tuple2(it.V1, dictionary.interpolate(it.V2))
        }

        final String interpolatedUrl = dictionary.interpolate(url)

        final String fullUrl = "${interpolatedUrl}${Utilities.paramsToString(interpolatedParams)}"

        var builder = HttpRequest.newBuilder()
            .uri(URI.create(fullUrl))
            .timeout(Duration.ofMinutes(2))

        headers.each {
            var name = it.key
            var value = dictionary.interpolate(it.value)
            if (config.printRequestHeaders()) {
                inBold {println("  ${name}: ${value}")}
            }
            builder.header(name, value)
        }

        var actualBody = ""

        if (body) {
            actualBody = dictionary.interpolate(body)

            if (config.printRequestBody()) {
                inSubtle {
                    println()
                    println(
                        Utilities.formatBodyText(actualBody,
                                                 headers['content-type']))
                    println()
                }
            }
        }

        builder.method(method.toString(),
                       HttpRequest.BodyPublishers.ofString(actualBody))

        builder.build()
    }

    Response sendRequest(Dictionary dictionary) {
        var interpolatedParams = params.collect {
            new Tuple2(it.V1, dictionary.interpolate(it.V2))
        }

        final def unencodedUrl = dictionary.interpolate(url) + Utilities.paramsToString(interpolatedParams, false)

        inColor GREEN, {
            println(">>> ${method} ${unencodedUrl}")
        }

        HttpRequest request = buildRequest(dictionary);
        HttpResponse response = null
        Utilities.timed "\nResponse Latency", {
            response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString()
            )
        }
        createResponse(response)
    }

    private Response createResponse(HttpResponse response) {
        Map<String, String> headers = [:]

        response.headers().map().each {
            // The first line of the response is represented as a MapEntry with
            // no key.
            if (it.key) {
                headers[it.key] = it.value[0]
            }
        }

        final var result = new Response(response.statusCode(),
                                  headers,
                                  response.body() as String)

        inColor result.succeeded() ? GREEN : RED, {
            println("<<< Status: ${result.statusCode}")
        }

        if (config.printResponseHeaders()) {
            inBold {
                result.headers.each {
                    println("  ${it.key}: ${it.value}")
                }
            }
            println()
        }

        if (config.printResponseBody()) {
            inSubtle {
                println(result.formattedBody())
            }
        }

        result
    }

    @Override
    String toString() {
        """${method} ${url}${Utilities.paramsToString(params)}
${Utilities.headersToString(headers)}
${body}
"""
    }

    Object methodMissing(String name, Object args) {
        throw new SyntaxError(
            "unexpected '${name}' while defining request.")
    }

    boolean canProvide(String name) {
        name in providers
    }

    String provide(Response response, String valueName) {
        providers[valueName].provideValueFrom(response)
    }

    Object provides(String valueName) {
        final RequestDSL request = this
        new HashMap<String, Object>() {
            Object from(String sourceType) {
                if (sourceType == 'responseBody') {
                    request.providers[valueName] = new InBody()
                } else {
                    return new ProviderDispatch(
                        request, valueName, sourceType)
                }
            }
        }
    }

    Object propertyMissing(String name) {
        // List of places where values can be retrieved from
        if (name in ["header", "json", "responseBody"])
            return "${name}"
        if (name in script.commands.keySet()) {
            return script.commands[name]
        }
        throw new SyntaxError("Unexpected source '${name}'")
    }

    Set<String> valueReferences() {
        var refs = new HashSet<String>()
        refs.addAll(Utilities.findValueReferences(url))
        refs
    }

    void checkDependencies() {
        if (dependency) {
            final Command command = dependency.dependsOn
            final dependencyRequests = command.requests
            final valueName = dependency.valueName
            final fulfillingRequest = dependencyRequests.find(req ->
                req.getProvider(valueName) != null)
            if (fulfillingRequest) {
                fulfillingRequest.checkDependencies();
            } else {
                throw new HapiException(
                    "Request: '${this}' depends on command: '${dependency.dependsOn.name}' for value: '${valueName}' which was not provided by the command.")
            }
        }
    }
}

/**
 * Selects the correct Provider based on the provider type
 */
@TypeChecked
class ProviderDispatch {
    private final RequestDSL request
    private final String valueName
    private final String sourceType

    ProviderDispatch(RequestDSL request,
                     String valueName,
                     String sourceType) {
        this.request = request
        this.valueName = valueName
        this.sourceType = sourceType
    }

    void propertyMissing(String sourceSpec) {
        switch(sourceType) {
            case "header":
                request.providers[valueName] =
                    new InHeader(sourceSpec)
                break

            case "json":
                request.providers[valueName] =
                    new InJson(sourceSpec)
                break

            default:
                throw new HapiException(
                    "unknown source '${sourceType}'")
        }
    }
}

@TypeChecked
class DictionarySource {
    RequestDSL request
    Response response

    DictionarySource(RequestDSL request, Response response) {
        this.request = request
        this.response = response
    }

    String getValue(String valueName) {
        var provider = request.getProvider(valueName)
        if (provider) {
             provider.provideValueFrom(response)
        }
    }

    @Override
    String toString() {
        request.describeProviders()
    }
}

/**
 * Provides a value from a Response (e.g. a header value,
 * JSON object, etc.)
 */
@TypeChecked
class Dictionary {
    List<DictionarySource> sources = []

    void addSource(DictionarySource source) {
        sources << source
    }

    Boolean hasValue(String valueName) {
        for (source in sources) {
            var value = source.getValue(valueName)
            if (value) {
                return true
            }
        }
        false
    }

    String getValue(String valueName) {
        for (source in sources.reverse()) {
            var value = source.getValue(valueName)
            if (value) {
                return value
            }
        }
        throw new HapiException("Dependency check failed. " +
                                "Could not find value named '${valueName}' in the " +
                                "following dependencies: ${sources.reverse()}")
    }

    String interpolate(String text) {
        Utilities.replaceValueReferences(text, { Object[] m ->
            String valueName = m[1] as String
            getValue(valueName)
        })
    }

    @Override
    String toString() {
        "Sources: ${sources}"
    }
}

@TypeChecked
abstract class Provider {
    abstract String provideValueFrom(Response response)
}

@TypeChecked
class InBody extends Provider {
    String provideValueFrom(Response response) {
        response.body
    }
}

@TypeChecked
class InHeader extends Provider {
    String headerName
    InHeader(String headerName) {
        this.headerName = headerName
    }

    String provideValueFrom(Response response) {
        response.headers[headerName]
    }
}

@TypeChecked
class InJson extends Provider {

    final String jsonPath

    InJson(String jsonPath) {
        this.jsonPath = jsonPath 
    }

    String provideValueFrom(Response response) {
        if (response.isJson()) {
            try {
                var json = response.toJson()
                Json.find(jsonPath, json)
            } catch (HapiException ex) {
                throw new HapiException("could not find JSON value at path " +
                                        "'${jsonPath}'.  ${ex.getMessage()}", ex)
            }
        } else {
            throw new HapiException("could not find JSON value at path '${jsonPath}' " +
                                    "in non-JSON body: '${response.toString()}'")
        }
    }
}

@TypeChecked
class Response {
    private final int statusCode
    private final String body
    private final Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER)
    // If the headers contain a 'Content-Type' header, this will be the value of that header.
    private String contentType

    Response(int statusCode,
             Map<String, String> headers,
             String body) {
        this.statusCode = statusCode
        this.headers = headers
        this.body = body
        this.contentType = this.headers.'content-type'
    }

    boolean succeeded() {
        statusCode in (200 .. 302)
    }

    Object toJson() {
        body ? new JsonSlurper().parseText(body) : null
    }

    boolean isJson() {
        contentType.startsWith("application/json")
    }

    String formattedBody() {
        Utilities.formatBodyText(body, headers["content-type"])
    }

    @Override
    String toString() {
        """Status Code: ${statusCode}
Headers: ${Utilities.headersToString(headers)}
Body: ${body}"""
    }
}

/**
 * Mix-in to provide access to process' environment variables.
 */
@TypeChecked
trait HasEnvironment {
    static String env(String envVarName, String defaultValue = null) {
        Utilities.getEnvVar(envVarName, defaultValue)
    }
}

/**
 * Adds request headers to the Request when they have a name and a  value.
 */
@TypeChecked
class HeaderDSL implements HasEnvironment {
    String name
    RequestDSL request

    HeaderDSL(RequestDSL request, String name) {
        this.request = request
        this.name = name
    }

    // called with the value of the header
    void propertyMissing(String value) {
        request.headers[name] = value
    }

    Object methodMissing(String name, Object args) {
        throw new SyntaxError("Unexepcted '${name}' while defining header")
    }
}

/**
 * Adds query string parameters to the Request when they have a name and a
 * value.
 */
@TypeChecked
class ParamDSL implements HasEnvironment {
    String name
    RequestDSL request

    ParamDSL(RequestDSL request, String name) {
        this.request = request
        this.name = name
    }

    // called with the value of the query string parameter
    void propertyMissing(String value) {
        request.params << new Tuple2(name, value)
    }
}

/**
 * Used to connect a Request that should provide a value to the Request that
 * needs the value.
 */
@TypeChecked
class Dependency {
    // A Command whose Request should provide a value
    final Command dependsOn

    // Command's Request should provide a value with this name.
    String valueName

    Dependency(Command dependsOn) {
        this.dependsOn = dependsOn
    }

    // Called with the name of the value.
    Dependency propertyMissing(String valueName) {
        this.valueName = valueName
        this
    }

    RequestDSL request() {
        dependsOn.request
    }
}

@TypeChecked
public class HapiException extends Exception {
    HapiException(String what) {
        super(what)
    }

    HapiException(String what, Exception cause) {
        super(what, cause)
    }
}

@TypeChecked
public class SyntaxError extends HapiException {
    SyntaxError(String what) {
        super(what)
    }
}

@TypeChecked
public class EvaluationError extends HapiException {
    EvaluationError(String what) {
        super(what)
    }
}
