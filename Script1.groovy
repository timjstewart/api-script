import static ApiScript.*

script {

    var token = env("SECRET_TOKEN", "2S3cre74Me!")

    config {
        printRequestHeaders  true
        printResponseHeaders true
        printRequestBody     true
        printResponseBody    true
    }

    var req1 = POST "http://httpbin.org/anything", {
        header 'Accept'       'application/json'
        header 'Content-Type' 'application/json'
        header 'SECRET'       env("SECRET_TOKEN", "SEcR3t")
        header 'User-Agent'   "links"
        param 'order'         "slug"
        param 'limit' 3
        param 'user'          env("USER", "anonymous")
        body '''
        {
            "title": "My Blog",
            "slug": "my-blog",
            "text": "This is my blog."
        }
        '''
        provides "host" from header "server";
        provides "host" from header "server";
        provides "verb" from json "method";
        provides "trace-id" from json "headers.X-Amzn-Trace-Id";
    }

    var req2 = GET "https://httpbin.org/anything", {
        header 'Accept' "application/json"
        header 'token' "{{verb}} {{host}} {{trace-id}}"
        header 'Content-Type' 'application/json'
        param "cache-buster" 12345
    }

    var req3 = GET "https://httpbin.org/anything", {
        provides "error" from responseBody
    }

    var req4 = POST "https://httpbin.org/status/401", {
        header 'Content-Type' 'application/json'
        body "{{error}}"
    }

    var jsonArrays = GET "https://httpbin.org/get", {
        param "limit" 5
        param "offset" 3
        param "offset" 4
        provides "offsets" from json "args.offset[1]"
    }

    var arrayUser = GET "https://httpbin.org/get", {
        param "limit" 5
        param "offset" "{{offsets}}"
    }

    group "Group 1", [
        req1,
        req2
    ]

    group "Group 2", [
        req3,
        req4
    ]

    group "JSON Arrays", [
        jsonArrays,
        arrayUser
    ]
}

