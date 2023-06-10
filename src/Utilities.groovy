import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.json.JsonException
import java.util.regex.Pattern
import java.util.regex.Matcher
import groovy.transform.TypeChecked

import org.fusesource.jansi.Ansi.Color
import org.fusesource.jansi.AnsiConsole
import static org.fusesource.jansi.Ansi.*
import static org.fusesource.jansi.Ansi.Color.*

trait HasStyle {
    static void inColor(Color color, Closure<Void> c) {
        print(ansi().fg(color))
        c.call()
        print(ansi().a(Attribute.RESET))
    }

    static void inBold(Closure c) {
        print(ansi().a(Attribute.INTENSITY_BOLD))
        c.call()
        print(ansi().a(Attribute.RESET))
    }

    static void inSubtle(Closure c) {
        print(ansi().a(Attribute.INTENSITY_FAINT))
        c.call()
        print(ansi().a(Attribute.RESET))
    }
}

@TypeChecked
class Utilities implements HasStyle {
    final static Pattern VALUE_NAME_REGEX = ~/\{\{([^}]*)\}\}/

    static headersToString(Map<String, String> headers) {
        headers.collect {"${it.key}: ${it.value}"}.join("\n")
    }

    static String paramsToString(List<Tuple2<String,String>> params, boolean encodeParams = true) {
        var result = params.collect {
            var name = it.V1
            var value = it.v2
            try {
                var json = new JsonSlurper().parseText(it.V2)
                value = JsonOutput.toJson(json)
            } catch (JsonException ex) {
                // use current value of value
            }
            var encoded = encodeParams ? java.net.URLEncoder.encode(value, "UTF-8") : value

            "${name}=${encoded}"
        }.join("&")
        result ? "?" + result : ""
    }

    static Set<String> findValueReferences(String text) {
        text.findAll(VALUE_NAME_REGEX).toSet()
    }

    static String replaceValueReferences(String text, Closure<String> c) {
        text.replaceAll(VALUE_NAME_REGEX, c)
    }

    static String getEnvVar(String name, String defaultValue) {
        String value = System.getenv(name)
        if (!value) {
            if (defaultValue) {
                println("Environment variable '${name}' not set.  Using default value.")
                return defaultValue
            } else {
                throw new EvaluationError("Environment Variable '${name}' not set.")
            }
        } else {
            value
        }
    }

    static <T> T timed(String operation, Closure<T> c) {
        def startTime = new Date().getTime()
        var result = c.call()
        def stopTime = new Date().getTime()
        inColor(BLUE) {
            println("${operation}: ${stopTime - startTime} milliseconds")
        }
        result
    }

    static formatBodyText(String text, String contentType = null) {
        if (contentType) {
            if (contentType.toLowerCase().startsWith("application/json")) {
                // You have a strange sense of pretty, JsonOutput.
                return JsonOutput.prettyPrint(text)
                    .split("\n")
                    .findAll { it.trim().length() > 0 }
                    .join("\n")
            }
        }
        return leftJustify(text)
    }

    static String leftJustify(String text) {
        var lines = text.split("\n")
        var shift = lines.collect { line ->
            line.toList().findIndexOf { it != ' ' }
        }.findAll { it > 0 }.min()
        if (shift) {
            lines.collect { it.length() > shift ? it[shift .. -1] : it }.join("\n")
        } else {
            text
        }
    }

    static void fatalError(Exception ex) {
        fatalError(ex.getMessage())
    }

    static void fatalError(String error) {
        inColor RED, {
            println(error)
        }
        println(ansi().a(Attribute.RESET))
        AnsiConsole.systemUninstall()
        System.exit(1)
    }
}
