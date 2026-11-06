#!/usr/bin/env python3
"""Real-model regression for paragraph-translation with PFML3 context/history.

No article text is uploaded to a cloud translation API. The GitHub runner
loads the 0.8B GGUF and sends prompt requests to its local llama.cpp server.
The test text matches the newly authored fictional regression paragraphs.
"""

import json
import pathlib
import re
import sys
import urllib.request

from smoke_pfml3 import HOST, wait_for_model

END_TOKENS = ["<|plamo:tag|>", "<|plamo:bos|>"]


def prompt_with_context(current, document, history, source_lang="en", target_lang="ja"):
    prompt = "<|plamo:tag|>dataset<|plamo:msg|>translation"
    prompt += "<|plamo:tag|>context<|plamo:msg|>" + document
    for original, translated in history[-2:]:
        prompt += (
            "<|plamo:tag|>input<|plamo:key|>lang<|plamo:val|>"
            + source_lang + "<|plamo:msg|>" + original
            + "<|plamo:tag|>output<|plamo:key|>lang<|plamo:val|>"
            + target_lang + "<|plamo:msg|>" + translated
        )
    prompt += (
        "<|plamo:tag|>input<|plamo:key|>lang<|plamo:val|>"
        + source_lang + "<|plamo:msg|>" + current
        + "<|plamo:tag|>output<|plamo:key|>lang<|plamo:val|>"
        + target_lang + "<|plamo:msg|>"
    )
    return prompt


def translate(original, document, history):
    payload = {
        "prompt": prompt_with_context(original, document, history),
        "temperature": 0,
        "n_predict": 512,
        "stream": True,
        "cache_prompt": False,
        "stop": END_TOKENS,
    }
    request = urllib.request.Request(
        HOST + "/completion",
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json; charset=utf-8",
                 "Accept": "text/event-stream"},
    )
    chunks = []
    ended = False
    stop_type = ""
    with urllib.request.urlopen(request, timeout=120) as response:
        for raw_line in response:
            line = raw_line.decode("utf-8").strip()
            if not line.startswith("data: "):
                continue
            datum = line[6:]
            if datum == "[DONE]":
                break
            if not datum:
                continue
            packet = json.loads(datum)
            if "error" in packet:
                raise AssertionError(str(packet["error"]))
            if packet.get("stop"):
                ended = True
                stop_type = packet.get("stop_type", "")
            chunks.append(packet.get("content", ""))
    assert ended, "Missing terminal stream event"
    assert stop_type != "limit", "Translation was terminated by n_predict limit"
    output = "".join(chunks).strip()
    assert output, "Translation returned no text"
    return output


def main():
    lines = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8").splitlines()
    paragraphs = [line.split(": ", 1)[1] for line in lines
                  if line.startswith("SEGMENT ")]
    assert len(paragraphs) == 3, f"Expected 3 intact paragraphs, got {len(paragraphs)}"
    document = "\n\n".join(paragraphs)
    wait_for_model()
    history = []
    for index, original in enumerate(paragraphs):
        text = translate(original, document, history)
        history.append((original, text))
        print(f"Article paragraph {index + 1}/3: {text}", flush=True)

    # These assertions specifically catch the user's observed corruption.
    last = history[-1][1]
    assert "来週" in last, f"The final next-week clause vanished: {last}"
    assert "弾劾" in last, f"The impeachment decision vanished: {last}"
    assert "休み" not in last, f"'Next week' was mistranslated as vacation: {last}"
    assert len(re.findall(r"[\u3040-\u30ff\u3400-\u9fff]", last)) > 20
    print("PASS: 3-paragraph fictional scenario retains impeachment and next-week context")


if __name__ == "__main__":
    main()
