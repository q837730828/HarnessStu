import datetime
import json
import sys


def schema():
    return {
        "tools": [
            {
                "name": "time_now",
                "description": "Return the current local timestamp from an external stdio tool.",
                "parameters": {
                    "type": "object",
                    "properties": {},
                    "required": [],
                    "additionalProperties": False,
                },
            },
            {
                "name": "echo",
                "description": "Echo a message through an external stdio tool.",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "message": {
                            "type": "string",
                            "description": "Message to echo.",
                        }
                    },
                    "required": ["message"],
                    "additionalProperties": False,
                },
            },
        ]
    }


def mcp_schema():
    converted = {"tools": []}
    for tool in schema()["tools"]:
        item = dict(tool)
        item["inputSchema"] = item.pop("parameters")
        converted["tools"].append(item)
    return converted


def call_tool(name, arguments):
    if name == "time_now":
        return {
            "ok": True,
            "content": datetime.datetime.now().isoformat(timespec="seconds"),
        }
    if name == "echo":
        return {
            "ok": True,
            "content": str(arguments.get("message", "")),
        }
    return {
        "ok": False,
        "content": "unknown tool: " + name,
    }


def mcp_text(text):
    return {
        "content": [
            {
                "type": "text",
                "text": text,
            }
        ],
        "isError": False,
    }


def handle_jsonrpc(request):
    method = request.get("method")
    request_id = request.get("id")

    if method == "initialize":
        return {
            "jsonrpc": "2.0",
            "id": request_id,
            "result": {
                "protocolVersion": "2025-11-25",
                "capabilities": {
                    "tools": {},
                    "resources": {},
                    "prompts": {}
                },
                "serverInfo": {
                    "name": "demo-tool-server",
                    "version": "0.1.0",
                },
            },
        }

    if method == "notifications/initialized":
        return None

    if method == "tools/list":
        return {
            "jsonrpc": "2.0",
            "id": request_id,
            "result": mcp_schema(),
        }

    if method == "tools/call":
        params = request.get("params") or {}
        response = call_tool(params.get("name", ""), params.get("arguments") or {})
        result = mcp_text(response.get("content", ""))
        if not response.get("ok", False):
            result["isError"] = True
        return {
            "jsonrpc": "2.0",
            "id": request_id,
            "result": result,
        }

    if method == "resources/list":
        return {
            "jsonrpc": "2.0",
            "id": request_id,
            "result": {
                "resources": [
                    {
                        "uri": "demo://about",
                        "name": "Demo About",
                        "description": "A tiny resource exposed by the demo MCP-style server.",
                        "mimeType": "text/plain",
                    }
                ]
            },
        }

    if method == "resources/read":
        params = request.get("params") or {}
        uri = params.get("uri", "")
        if uri == "demo://about":
            return {
                "jsonrpc": "2.0",
                "id": request_id,
                "result": {
                    "contents": [
                        {
                            "uri": uri,
                            "mimeType": "text/plain",
                            "text": "This resource comes from the demo MCP-style stdio server.",
                        }
                    ]
                },
            }
        return {
            "jsonrpc": "2.0",
            "id": request_id,
            "error": {
                "code": -32002,
                "message": "resource not found: " + uri,
            },
        }

    if method == "prompts/list":
        return {
            "jsonrpc": "2.0",
            "id": request_id,
            "result": {
                "prompts": [
                    {
                        "name": "demo_review",
                        "description": "A demo prompt returned by the MCP-style server.",
                        "arguments": [
                            {
                                "name": "topic",
                                "description": "Topic to review.",
                                "required": False,
                            }
                        ],
                    }
                ]
            },
        }

    if method == "prompts/get":
        params = request.get("params") or {}
        arguments = params.get("arguments") or {}
        topic = arguments.get("topic", "this harness")
        return {
            "jsonrpc": "2.0",
            "id": request_id,
            "result": {
                "description": "Demo review prompt",
                "messages": [
                    {
                        "role": "user",
                        "content": {
                            "type": "text",
                            "text": "Review " + topic + " and summarize the key risks.",
                        },
                    }
                ],
            },
        }

    return {
        "jsonrpc": "2.0",
        "id": request_id,
        "error": {
            "code": -32601,
            "message": "method not found: " + str(method),
        },
    }


def main():
    for line in sys.stdin:
        if not line.strip():
            continue
        request = json.loads(line)

        if request.get("jsonrpc") == "2.0":
            response = handle_jsonrpc(request)
            if response is not None:
                print(json.dumps(response, ensure_ascii=False), flush=True)
            continue

        if request.get("type") == "list_tools":
            print(json.dumps(schema(), ensure_ascii=False), flush=True)
            return
        if request.get("type") == "call_tool":
            response = call_tool(request.get("name", ""), request.get("arguments") or {})
            print(json.dumps(response, ensure_ascii=False), flush=True)
            return
        print(json.dumps({"ok": False, "content": "unknown request type"}, ensure_ascii=False), flush=True)
        return


if __name__ == "__main__":
    main()
