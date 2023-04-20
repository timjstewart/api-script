# Hapi

## Prerequisites

* Groovy 4 bin directory in your $PATH.

## Introduction

This tool allows you to define "conversational" interactions with HTTPS servers where the data returned from one request can be collected and then used in a subsequent request.

# Creating a script

Every script needs to have the following text (you can omit the comments):

```
// Import Hapi
import static Hapi.*

script {
   // Your config, commands and groups are defined in here.
}
```

## Script-level Configuration

```
script {
    config {
        printRequestHeaders  true
        printResponseHeaders true
        printRequestBody     true
        printResponseBody    true
        logResponseBody      false
    }
  }
```
You can configure whether or not request and/or response bodies are printed and whether or not request and/or response headers are printed by setting these boolean settings to `true` or `false`.

If logResponseBody is set to true, responses are logged to the `hapi-logs` directory.

# Syntax

Within your script block you can define requests.

## Define a simple GET request

```
script {
  GET simpleGet, "https://httpbin.org/anything"
}
```

This line defines a request that can be referred to as simpleGet that will perform a GET request to the URL above.


## Define a simple DELETE request

```
script {
  DELETE simpleDelete, "https://httpbin.org/anything"
}
```

## Sending headers

To send headers, you will need to provide a request block that defines the headers.  Make sure to include the `,` after the URL.

```
script {
  POST sendHeaders, "https://httpbin.org/status", {
      header 'Content-Type' 'application/json'
      header 'Accept'       'text/css'
  }
}
```

## Sending query string parameters

```
script {
  GET getWithParams, "https://httpbin.org/status", {
      param 'cups'       3
      param 'convertTo'  'teaspoons'
  }
}
```

You could also include the parameters in the URL:

```
script {
  GET getWithParams, "https://httpbin.org/status?cups=3&convertTo=teaspoons"
}
```

Query string parameters will be encoded automatically for you if you include them as separate parameters as in the first example.

Note: If you specify multiple query string parameters with the same name, the query string parameter will be included multiple times.

## Sending Headers and Query String Parameters

```
script {
  POST sendHeaders, "https://httpbin.org/status", {
      param 'cups'          3
      header 'Accept'       'application/json
      param 'convertTo'     'teaspoons'
  }
  // other lines omitted
}
```

Headers can be combined with query string parameters.

# Extracting data from Responses

You can specify that a response will provide a value with a specific name.  That value can then be accessed in subsequent requests by enclosing the name in double curly braces (e.g. {{serverName}}`).

## From the Response JSON

```
script {
  GET jsonArrays, "https://httpbin.org/get", {
      param "offset" 3
      param "offset" 4
      provides "offsets" from json "args.offset[1]"
  }
}
```

This request assumes that the response will have a structure similar to the following:

```
{
   "args": {
      "offset": [
         3,
         4
      ]
   }
}
```

and the `provides "offsets" from json "args.offset[1]"` line says that a value, to be named `offsets` will be found in the response JSON at the specified path.

## From the Response Body Text

```
script {
  GET jsonArrays, "https://httpbin.org/get", {
      provides "text" from responseBody
  }
}
```

The `provides "text" from responseBody` syntax says that a value, to be named `text` will be set to the entire response body interpreted as text.

## From the Response Header

```
script {
  GET jsonArrays, "https://httpbin.org/get", {
      provides "serverName" from header "X-Server"}
  }
}
```

The `provides "serverName" from header "X-Server"` syntax specifies that a value with the name `serverName` will be extracted from a response header named `X-Server`.


# Dependencies

If one or more requests require values from other requests, you can specify that dependency via the `dependsOn` statement.

Here is an example of a request that creates a token and a request that requires that token.

```
script {
  POST acquiresToken, "https://httpbin.org/anything", {
      provides "token" from json "json.token"
      body '''{
        "token": "SecretToken@"
      }'''
  }

  GET requiresToken, "https://httpbin.org/get", {
      header      "token" "{{token}}"
      dependsOn   acquiresToken "token"
  }
}
```

Then, if you execute the `requiresToken` request, it will automatically run the acquiresToken request first and retrieve the token from its response and then use that token for the `requiresToken` request.

# Groups of requests

You can define a group of requests where each request is run in the order it is defined.

For example:

```
script {
  // definitions of acquiresToken and requiresToken omitted (see above)

  group "tokenTest", [
      acquiresToken,
      requiresToken
  ]
}
```

Given this group, you could run all of the requests in the group by passing `tokenTest` to the tool via command line.

# Running Scripts

## Listing available commands

```
./hapi SCRIPT
```

## Executing a group of requests or a sinble request.

```
./hapi SCRIPT GROUP_OR_REQUEST
```

# Sensitive Data

You can store sensitive data in an environment variable and access it via the `env` function.  The `env` function has two overloads.  The first overload takes one parameter, the name of the environment varaible whose value you want to use.  The second overload is like the first but it provides an additional parameter that is the value that should be used if the enironment variable has no value.

# To Do

* Config specifies whether or not to follow redirects
