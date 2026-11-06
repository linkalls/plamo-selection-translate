#!/usr/bin/env python3
"""Linux CI smoke test for the exact PFML3 prompt used by the Android app.

This script deliberately does not call any cloud translation APIs. It submits
two tiny translation tasks to the same llama.cpp /completion endpoint that the
Android release uses. An incorrect chat template, target language, or server
endpoint causes CI to fail with the returned text.
"""

import json
import re
import sys
import time
import urllib.error
import urllib.request

HOST = "http://127.0.0.1:8765"
STOP = ["<|plamo:tag|>", "<|plamo:bos|>"]


def http_json(path: str, data: dict | None = None, timeout: int = 30) -> dict:
    """Perform an HTTP call without additional package dependencies."""
    payload = json.dumps(data, ensure_ascii=False).encode("utf-8") if data else None
    request = urllib.request.Request(
        HOST + path,
        data=payload,
        headers={"Content-Type": "application/json; charset=utf-8"},
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.load(response)


def wait_for_model(max_seconds: int = 110) -> None:
    """wait for llama.cpp to have loaded the quantized model on the CI runner."""
    until = time.monotonic() + max_seconds
    while time.monotonic() < until:
        try:
            response = http_json("/health", timeout=2)
            if response.get("status") == "ok":
                return
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError):
            pass
        time.sleep(1)
    raise RuntimeError("llama.cpp did not become ready in 110 seconds")


def pfml3(text: str, target: str, source: str | None) -> str:
    """Build the exact recommended PFML3 prompt without adding a BOS token."""
    prompt = "<|plamo:tag|>dataset<|plamo:msg|>translation"
    prompt += "<|plamo:tag|>input"
    if source:
        prompt += "<|plamo:key|>lang<|plamo:val|>" + source
    prompt += "<|plamo:msg|>" + text
    prompt += "<|plamo:tag|>output"
    prompt += "<|plamo:key|>lang<|plamo:val|>" + target
    prompt += "<|plamo:msg|>"
    return prompt


def translate(text: str, target: str, source: str | None) -> str:
    """The Android app sends n_predict=1536; use 200 for the short smoke test."""
    response = http_json(
        "/completion",
        {
            "prompt": pfml3(text, target, source),
            "temperature": 0,
            "n_predict": 200,
            "cache_prompt": False,
            "stop": STOP,
        },
        timeout=120,
    )
    result = response.get("content", "").strip()
    if not result:
        raise AssertionError(f"Empty translation: {response}")
    return result


def translate_stream(text: str, target: str, source: str | None) -> str:
    """Exercise the native SSE format consumed by Android's streaming UI."""
    data = {
        "prompt": pfml3(text, target, source),
        "temperature": 0,
        "n_predict": 200,
        "cache_prompt": False,
        "stop": STOP,
        "stream": True,
    }
    request = urllib.request.Request(
        HOST + "/completion",
        data=json.dumps(data, ensure_ascii=False).encode("utf-8"),
        headers={
            "Content-Type": "application/json; charset=utf-8",
            "Accept": "text/event-stream",
        },
    )
    tokens = []
    event_count = 0
    terminal_stop = None
    with urllib.request.urlopen(request, timeout=120) as response:
        for binary_line in response:
            line = binary_line.decode("utf-8").strip()
            if not line.startswith("data: "):
                continue
            value = line[6:]
            if value == "[DONE]":
                break
            if not value:
                continue
            payload = json.loads(value)
            if "error" in payload:
                raise AssertionError(f"Streaming failure: {payload['error']}")
            if payload.get("stop") is True:
                terminal_stop = payload.get("stop_type")
            content = payload.get("content", "")
            if content:
                event_count += 1
                tokens.append(content)
    result = "".join(tokens)
    assert event_count > 0, "SSE returned no token events"
    assert terminal_stop is not None, "SSE ended without a final stop event"
    assert terminal_stop != "limit", f"Generation stopped by token limit: {result}"
    return result.strip()


def main() -> None:
    wait_for_model()

    english_source = "The laboratory team completed the experiment before sunset."
    japanese_output = translate(english_source, "ja", "en")
    print("English -> Japanese:", japanese_output)
    japanese_characters = re.findall(r"[\u3040-\u30ff\u3400-\u9fff]", japanese_output)
    assert len(japanese_characters) >= 6, (
        "English text was not translated into Japanese: " + japanese_output
    )

    japanese_source = "会議は来週の月曜日に変更されました。"
    english_output = translate(japanese_source, "en", "ja")
    print("Japanese -> English:", english_output)
    assert len(re.findall(r"[A-Za-z]", english_output)) >= 15, (
        "Japanese text was not translated into English: " + english_output
    )

    streaming = translate_stream(
        "The engineer repaired the damaged bridge yesterday.", "ja", "en"
    )
    print("Android-style streaming English -> Japanese:", streaming)
    assert len(re.findall(r"[\u3040-\u30ff\u3400-\u9fff]", streaming)) >= 5, (
        "Streaming output lost Japanese characters: " + streaming
    )

    # This request exercises auto source detection: no lang field on input.
    automatic_output = translate("The science museum opened a new gallery.", "ja", None)
    print("Auto-detect English -> Japanese:", automatic_output)
    assert len(re.findall(r"[\u3040-\u30ff\u3400-\u9fff]", automatic_output)) >= 4, (
        "Automatic source language detection failed: " + automatic_output
    )
    print("PASS: both directions, auto-detection, and streamed translation generated target-script text.")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print("FAIL:", repr(error), file=sys.stderr)
        sys.exit(1)
