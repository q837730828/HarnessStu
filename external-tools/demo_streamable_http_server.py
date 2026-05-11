from http.server import BaseHTTPRequestHandler, HTTPServer
import datetime
import json
import sys
import uuid


def tools_schema():
    return {
        "tools": [
            {
                "name": "time_now",
                "description": "Return the current local timestamp from a Streamable HTTP MCP-style server.",
                "inputSchema": {
                    "type": "object",
                    "properties": {},
                    "required": [],
                    "additionalProperties": False,
                },
            },
            {
                "name": "echo",
                "description": "Echo a message through a Streamable HTTP MCP-style server.",
                "inputSchema": {
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


def text_result(text, is_error=False):
    return {
        "content": [{"type": "text", "text": text}],
        "isError": is_error,
    }


def call_tool(name, arguments):
    if name == "time_now":
        return text_result(datetime.datetime.now().isoformat(timespec="seconds"))
    if name == "echo":
        return text_result(str(arguments.get("message", "")))
    return text_result("unknown tool: " + name, True)


def handle_jsonrpc(request):
    method = request.get("method")
    request_id = request.get("id")

    if method == "initialize":
        return {
            "jsonrpc": "2.0",
            "id": request_id,
            "result": {
                "protocolVersion": "2025-11-25",
                "capabilities": {"tools": {}, "resources": {}, "prompts": {}},
                "serverInfo": {"name": "demo-streamable-http-server", "version": "0.1.0"},
            },
        }
    if method == "notifications/initialized":
        return None
    if method == "tools/list":
        return {"jsonrpc": "2.0", "id": request_id, "result": tools_schema()}
    if method == "tools/call":
        params = request.get("params") or {}
        return {
            "jsonrpc": "2.0",
            "id": request_id,
            "result": call_tool(params.get("name", ""), params.get("arguments") or {}),
        }
    if method == "resources/list":
        return {
            "jsonrpc": "2.0",
            "id": request_id,
            "result": {
                "resources": [
                    {
                        "uri": "demo-http://about",
                        "name": "Demo HTTP About",
                        "description": "A resource exposed by the demo Streamable HTTP server.",
                        "mimeType": "text/plain",
                    }
                ]
            },
        }
    if method == "resources/read":
        uri = (request.get("params") or {}).get("uri", "")
        return {
            "jsonrpc": "2.0",
            "id": request_id,
            "result": {
                "contents": [
                    {
                        "uri": uri,
                        "mimeType": "text/plain",
                        "text": "This resource comes from the demo Streamable HTTP MCP-style server.",
                    }
                ]
            },
        }
    if method == "prompts/list":
        return {
            "jsonrpc": "2.0",
            "id": request_id,
            "result": {
                "prompts": [
                    {
                        "name": "demo_http_review",
                        "description": "A demo prompt returned by the Streamable HTTP server.",
                        "arguments": [{"name": "topic", "description": "Topic to review.", "required": False}],
                    }
                ]
            },
        }
    if method == "prompts/get":
        params = request.get("params") or {}
        topic = (params.get("arguments") or {}).get("topic", "this harness")
        return {
            "jsonrpc": "2.0",
            "id": request_id,
            "result": {
                "description": "Demo HTTP review prompt",
                "messages": [
                    {
                        "role": "user",
                        "content": {"type": "text", "text": "Review " + topic + " from HTTP transport."},
                    }
                ],
            },
        }
    return {
        "jsonrpc": "2.0",
        "id": request_id,
        "error": {"code": -32601, "message": "method not found: " + str(method)},
    }


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length).decode("utf-8")
        request = json.loads(body) if body else {}
        response = handle_jsonrpc(request)

        if response is None:
            self.send_response(202)
            self.send_header("Mcp-Session-Id", self.headers.get("Mcp-Session-Id") or str(uuid.uuid4()))
            self.end_headers()
            return

        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Mcp-Session-Id", self.headers.get("Mcp-Session-Id") or str(uuid.uuid4()))
        self.end_headers()
        self.wfile.write(json.dumps(response, ensure_ascii=False).encode("utf-8"))

    def log_message(self, format, *args):
        return


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8765
    HTTPServer(("127.0.0.1", port), Handler).serve_forever()


if __name__ == "__main__":
    main()
