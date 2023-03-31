import groovy.json.JsonSlurper


class Dsl {

    enum Method {
        GET, POST, PUT, HEAD, DELETE, PATCH, OPTIONS
    };

    static Request get(String url,
                       @DelegatesTo(Request) Closure c) {
        return request(Method.GET, url, c);
    }

    static Request put(String url,
                       @DelegatesTo(Request) Closure c) {
        return request(Method.PUT, url, c);
    }

    static Request post(String url,
                       @DelegatesTo(Request) Closure c) {
        return request(Method.POST, url, c);
    }

    // static Request get(String url) {
    // }

    static void runIt(Request... requests) {
        Store store = new Store() 
        store.put("token", "12345")
        for (req in requests) {
            var resp = req.send(store)
        }
    }

    private static Request request(
        Method method, String url,
        @DelegatesTo(Request) Closure c) {
        var req = new Dsl.Request(method, url);
        c.delegate = req
        c.resolveStrategy = Closure.DELEGATE_ONLY;
        c.call()
        return req;
    }

    static class Store {
        Map<String, Object> store = new HashMap<>()
        Object get(String key) {
            return store[key]
        }
        void put(String key, Object o) {
            store[key] = o
        }

    }

    static abstract class ParamValue {
        abstract String value(final Store store)
    }

    static class ParamWithValue extends ParamValue {
        String value
        ParamWithValue(String _value) {
            this.value = value
        }
        String value(final Store store) {
            return value
        }
    }

    // static abstract class ValueFromStore {
    //     String key
    //     ValueFromStore(String key) {
    //         this.key = key
    //     }
    //     String value(final Store store) {
    //         return store.get(key)
    //     }
    // }

    static class Request {
        Method method;
        String url
        Map<String, Value> headers = [:];
        List<Tuple2<String, ParamValue>> params = new ArrayList<>()
        Object body

        Request(Method method, String url) {
            this.method = method;
            this.url = url
        }

        @Override
        public String toString() {
            return this.method.name() + " " + this.url
        }

        public void headers(Map<String, Object> headers) {
            headers.each {
                String key = it.key
                Object value = it.value
                if (value instanceof String) {
                    this.headers[key] = new StaticValue(value)
                } else if (value instanceof Value){
                    this.headers[key] = value
                } else {
                    println("Unexpected type: ${value.class}")
                }
            }
        }

        public void param(String name, String value) {
            params.add(new Tuple2<String, ParamValue>(
                name, new ParamWithValue(value)));
        }

        public void json(String jsonText) {
            this.body = new JsonSlurper().parseText(jsonText);
        }

        static StoreWriter store(String key) {
            return new StoreWriter(key)
        }

        static String jpath(String path) {
            return null;
        }

        static String get(String path) {
            return "hi"
        }

        static Value from(String key) {
            return new DynamicValue(key)
        }

        Response send(Store store) {
            println("URL: ${method} ${url}")
            for (param in params) {
                println("  P: ${param.V1}=${param.V2.value(store)}")
            }
            headers.each {
                println("  H: ${it.key}=${it.value.get(store)}")
            }
            println("  ${body}")

            return new Response([:], "hi", 200)
        }
    }

    static class StoreWriter {
        String key
        StoreWriter(String key) {
            this.key = key
        }

        JPath jpath(String path) {
            return new JPath(path)
        }
    }

    static class JPath {
        String path
        JPath(String path) {
            this.path = path
        }
    }

    static abstract class Value {
        abstract String get(final Store store)
    }

    static class StaticValue extends Value {
        public String value
        StaticValue(String value) {
            this.value = value
        }
        String get(final Store _store) {
            return value
        }
    }

    static class DynamicValue extends Value {
        String keyName
        DynamicValue(String keyName) {
            this.keyName = keyName
        }
        String get(final Store store) {
            return store.get(keyName)
        }
    }

    static class Response {
        int status
        Map<String, Value> headers = [:]
        Object body

        Response(Map<String, Value> h,
                 Object body,
                 int status) {
            this.headers = h
            this.body = body
            this.status = status
        }
    }

}


