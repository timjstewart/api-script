import java.util.regex.Pattern
import java.util.regex.Matcher

import groovy.transform.TypeChecked

@TypeChecked
class PathElement {
    boolean isProperty() { false }
    boolean isArrayIndex() { false }
}

@TypeChecked
class Property extends PathElement {
    private final String name
    Property(String name) { this.name = name }
    boolean isProperty() { true }
    @Override String toString() { name }
}

@TypeChecked
class ArrayIndex extends PathElement {
    private final int index
    ArrayIndex(int index) { this.index = index }
    boolean isArrayIndex() { true }
    @Override String toString() { index.toString() }
}

@TypeChecked
class Json {
    static String find(String path, Object json) {
        final def parsed = parseJsonPath(path)
        extractJsonValue(parsed, json)
    }

    private static String extractJsonValue(List<PathElement> path, Object json) {
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
                        throw new HapiException("Array ${json} only has ${list.size()} elements in " +
                                                "it but element at index ${index} was requested.")
                    }
                } else {
                    throw new HapiException("Javascript value '${head}' is not a valid array index for '${json}'.")
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
    private static List<PathElement> tokenize(String s) {
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
