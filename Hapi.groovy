import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.regex.Pattern
import java.util.regex.Matcher

import groovy.json.JsonSlurper
import groovy.transform.TypeChecked

import org.fusesource.jansi.Ansi.Color
import org.fusesource.jansi.AnsiConsole
import static org.fusesource.jansi.Ansi.*
import static org.fusesource.jansi.Ansi.Color.*

import static Utilities.*

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
    static void script(@DelegatesTo(Statements) Closure<Void> c) {
        Terminal.init()

        var args = getArguments()

        var statements = new Statements()
        c.delegate = statements
        c.resolveStrategy = Closure.DELEGATE_ONLY
        c.call()

        if (args.length == 0) {
            statements.printAvailableCommands()
        } else {
            var commandName = args[0]
            if (commandName in statements.commands.keySet()) {
                statements.run(commandName)
            } else if (commandName in statements.groups.keySet()) {
                statements.run(commandName)
            } else {
                println("Unknown command '${commandName}'")
                statements.printAvailableCommands()
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
class Command {
    private final String name
    private RequestDSL request

    Command(String name) {
        this.name = name
    }

    @Override
    String toString() {
        return "Command: ${name} hasRequest: ${request != null}"
    }
}

@TypeChecked
class Statements implements HasStyle {
    private Map<String, List<Command>> groups = [:]
    private Map<String, Command> commands = [:]
    private Config _config = new Config()

    RequestDSL DELETE(Command command, String url, Closure<RequestDSL> c = null) {return request(command, Method.DELETE, url, c)}
    RequestDSL GET(Command command, String url, Closure<RequestDSL> c = null) {return request(command, Method.GET, url, c)}
    RequestDSL HEAD(Command command, String url, Closure<RequestDSL> c = null) {return request(command, Method.HEAD, url, c)}
    RequestDSL OPTIONS(Command command, String url, Closure<RequestDSL> c = null) {return request(command, Method.OPTIONS, url, c)}
    RequestDSL PATCH(Command command, String url, Closure<RequestDSL> c = null) {return request(command, Method.PATCH, url, c)}
    RequestDSL POST(Command command, String url, Closure<RequestDSL> c = null) {return request(command, Method.POST, url, c)}
    RequestDSL PUT(Command command, String url, Closure<RequestDSL> c = null) {return request(command, Method.PUT, url, c)}

    void printAvailableCommands() {
        // TODO: After unifying groups and commands, print message if no
        // commands were found.
        println("Available commands:")
        groups.keySet().each {
            println(" - ${it}")
        }
        commands.keySet().each {
            println(" - ${it}")
        }
    }

    String env(String envVarName, String defaultValue = null) {
        try {
            Utilities.getEnvVar(envVarName, defaultValue)
        } catch (HapiException ex) {
            Utilities.fatalError(ex.getMessage())
        }
    }

    void group(String name, List<Command> commands) {
        groups[name] = commands
    }

    void run(String commandName) {
        if (commandName in commands) {
            runCommand([commands[commandName].request])
        } else if (commandName in groups) {
            runCommand(groups[commandName].collect{it.request})
        } else {
            Utilities.fatalError("Could not run '${commandName}'.")
        }
    }

    void runCommand(List<RequestDSL> requests) {
        try {
            checkDependencies(requests)
            final var dictionary = new Dictionary()
            requests.each {
                if (it.tokenSource) {
                    ensureToken(it.tokenSource, dictionary)
                }
                final Response response = it.sendRequest(dictionary)
                dictionary.addSource(new DictionarySource(it, response))
                println()
            }
        } catch (HapiException ex) {
            Utilities.fatalError(ex.getMessage())
        }
    }

    private ensureToken(TokenSource tokenSource, Dictionary dictionary) {
        final String tokenName = tokenSource.tokenName
        final Command command = tokenSource.command
        if (command) {
            final RequestDSL request = command.request
            if (!dictionary.hasValue(tokenName)) {
                final Response tokenResponse = request.sendRequest(dictionary)
                dictionary.addSource(
                    new DictionarySource(request, tokenResponse))
                if(!dictionary.hasValue(tokenName)) {
                    throw new EvaluationError(
                        "Could not acquire token named '${tokenName}' from " +
                            "tokenSource request '${request.url}'.")
                }
            }
        }
    }

    void config(@DelegatesTo(Config) Closure<Void> c) {
        c.delegate = _config
        c.resolveStrategy = Closure.DELEGATE_ONLY
        c.call()
    }

    private static void checkDependencies(List<RequestDSL> requests) {
        var providedValues = new HashSet<String>()

        requests.collate(2, 1).each {
            var providingRequest = it[0]
            var requiringRequest = it[1]

            // no dependency to check
            if (!requiringRequest)
                return

            providedValues.addAll(
                providingRequest.providers.keySet()
            )

            if (requiringRequest.tokenSource) {
                providedValues.addAll(
                    requiringRequest.tokenSource.command.request.providers.keySet()
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

    private RequestDSL request(
        Command command,
        Method method,
        String url,
        @DelegatesTo(RequestDSL) Closure<RequestDSL> c) {

        final var dsl = new RequestDSL(this, _config, method, url)

        command.request = dsl
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
    Command propertyMissing(String name) {
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
        groups.each {
            println("Group: ${it}")
        }
        commands.each {
            println("Command: ${it}")
        }

        return sb.toString()
    }
}

@TypeChecked
class RequestDSL implements HasStyle {
    private static HttpClient httpClient = HttpClient.newBuilder() .build()

    private final Method method
    private final String url
    private final List<Tuple2<String, String>> params = new ArrayList<>()
    private final Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER)
    private String body

    private final Map<String, Provider> providers = [:]
    private final Config config

    private TokenSource tokenSource
    private Statements statements

    RequestDSL(Statements statements, Config config, Method method, String url) {
        this.statements = statements
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

    Provider getProvider(String valueName) {
        return providers[valueName]
    }

    String describeProviders() {
        "${url} - ${providers.keySet()}"
    }

    TokenSource tokenSource(Command request) {
        TokenSource source = new TokenSource(request)
        tokenSource = source
        return source
    }

    private HttpRequest buildRequest(Dictionary dictionary) {
        var interpolatedParams = params.collect {
            new Tuple2(it.V1, dictionary.interpolate(it.V2))
        }

        final String fullUrl = "${url}${Utilities.paramsToString(interpolatedParams)}"

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

        return builder.build()
    }

    Response sendRequest(Dictionary dictionary) {
        var interpolatedParams = params.collect {
            new Tuple2(it.V1, dictionary.interpolate(it.V2))
        }

        final def unencodedUrl = url + Utilities.paramsToString(interpolatedParams, false)

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

        var result = new Response(response.statusCode(),
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
        return """${method} ${url}${Utilities.paramsToString(params)}
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
        if (name in statements.commands.keySet()) {
            return statements.commands[name]
        }
        throw new SyntaxError("Unexpected source '${name}'")
    }

    Set<String> valueReferences() {
        var refs = new HashSet<String>()
        refs.addAll(Utilities.findValueReferences(url))
        refs
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

    // TODO: Is reverse() necessary here? It's needed for getValue() to ensure
    //       that the most recent source's value is used.
    Boolean hasValue(String valueName) {
        for (source in sources.reverse()) {
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
        throw new HapiException("Value dependency check failed. " +
                                     "Could not find value '${valueName}' in the " +
                                     "following requests: ${sources.reverse()}")
    }

    String interpolate(String text) {
        Utilities.replaceValueReferences(text, { Object[] m ->
            String valueName = m[1] as String
            getValue(valueName)
        })
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

    @TypeChecked
    static class PathElement {
        boolean isProperty() { false }
        boolean isArrayIndex() { false }
    }

    @TypeChecked
    static class Property extends PathElement {
        private final String name
        Property(String name) { this.name = name }
        boolean isProperty() { true }
        @Override String toString() { name }
    }

    @TypeChecked
    static class ArrayIndex extends PathElement {
        private final int index
        ArrayIndex(int index) { this.index = index }
        boolean isArrayIndex() { true }
        @Override String toString() { index.toString() }
    }

    final List<PathElement> jsonPath

    InJson(String jsonPath) {
        this.jsonPath = parseJsonPath(jsonPath)
    }

    String provideValueFrom(Response response) {
        if (response.isJson()) {
            try {
                var json = response.toJson()
                extractJsonValue(jsonPath, json)
            } catch (HapiException ex) {
                throw new HapiException("could not find JSON value at path '${jsonPath}'.  ${ex.getMessage()}", ex)
            }
        } else {
            throw new HapiException("could not find JSON value at path '${jsonPath}' in non-JSON body: '${response.toString()}'")
        }
    }

    private String extractJsonValue(List<PathElement> path, Object json) {
        if (path.isEmpty()) {
            // We have arrived at the JSON element to extract.
            if (json instanceof String || json instanceof Float || json instanceof Boolean) {
                return json.toString()
            } else {
                throw new HapiException("found non-scalar at path '${json}'")
            }
        } else {
            // head specifies the JSON element we're looking for in `json`.
            var head = path.head()
            if (json instanceof Map) {
                // Look for head key in JSON object.
                String property = head.toString()
                var obj = json as Map<String, Object>
                if (property in obj) {
                    extractJsonValue(path.tail(), obj[property]) 
                } else {
                    throw new HapiException("key '${property}' not found in json: '${json}'")
                }
            } else if (json instanceof List) {
                // Look for head array index in JSON list.
                List list = json as List
                if (head.isArrayIndex()) {
                    int index = (head as ArrayIndex).index
                    if (index < list.size()) {
                        return extractJsonValue(path.tail(), json[index])
                    } else {
                        throw new HapiException("array ${json} only has ${list.size()} elements in it but element at index ${index} was requested.")
                    }
                } else {
                    throw new HapiException("${head} is not a valid array index for '${json}'.")
                }
            } else {
                throw new HapiException("key '${head}' not found in non-object: '${json}'")
            }
        }
    }

    /**
     * Given a String like "name", returns ["name"].
     * Given a String like "name[3]", returns ["name", 3].
     * Given a String like "[3]", returns [3].
     */
    static private List<PathElement> tokenize(String s) {
        final Pattern pattern = Pattern.compile(/(\w+)?\[(\d+)\]/)
        final Matcher m = s =~ pattern
        if (m.matches()) {
            if (m.group(1)) {
                [
                    new Property(m.group(1)),
                    new ArrayIndex(m.group(2).toInteger())
                ]
            } else {
                [
                    new ArrayIndex(m.group(2).toInteger())
                ]
            }
        } else {
            [
                new Property(s)
            ]
        }
    }

    static List<PathElement> parseJsonPath(String path) {
        path.split(/\./).collectMany{tokenize(it)}
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
        if (body) {
            return new JsonSlurper().parseText(body)
        }
        return null
    }

    boolean isJson() {
        return contentType.startsWith("application/json")
    }

    String formattedBody() {
        Utilities.formatBodyText(body, headers["content-type"])
    }

    @Override
    String toString() {
        return """Status Code: ${statusCode}
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
 * Used to connect a Request that should provide a token to the Request that
 * needs the token.
 */
@TypeChecked
class TokenSource {
    // A Command whose Request should provide a token
    final Command command

    // `command`'s Request should provide a token with this name.
    String tokenName

    TokenSource(Command command) {
        this.command = command
    }

    // Called with the name of the token.
    TokenSource propertyMissing(String tokenName) {
        tokenName = tokenName
        return this
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
