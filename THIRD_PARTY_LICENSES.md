# Third-Party Licenses

## Mozc - Japanese Input Method

Copyright 2010-2024, Google Inc.
All rights reserved.

License: BSD 3-Clause

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:

  * Redistributions of source code must retain the above copyright
    notice, this list of conditions and the following disclaimer.
  * Redistributions in binary form must reproduce the above
    copyright notice, this list of conditions and the following disclaimer
    in the documentation and/or other materials provided with the
    distribution.
  * Neither the name of Google Inc. nor the names of its
    contributors may be used to endorse or promote products derived from
    this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

Repository: https://github.com/google/mozc

Usage in Nacre: Dictionary data for Japanese kana-kanji conversion.

---

## whisper.cpp

Copyright (c) 2023 Georgi Gerganov

License: MIT

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions: The above copyright
notice and this permission notice shall be included in all copies or
substantial portions of the Software.

Repository: https://github.com/ggerganov/whisper.cpp

Usage in Nacre: On-device speech recognition for voice input.

---

## llama.cpp

Copyright (c) 2023 Georgi Gerganov

License: MIT

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions: The above copyright
notice and this permission notice shall be included in all copies or
substantial portions of the Software.

Repository: https://github.com/ggerganov/llama.cpp

Usage in Nacre: Local LLM inference for text transformation and candidate reranking.

---

## KenLM

Copyright (c) 2011 Kenneth Heafield

License: LGPL-2.1

This library is free software; you can redistribute it and/or modify it
under the terms of the GNU Lesser General Public License as published by
the Free Software Foundation; either version 2.1 of the License, or (at
your option) any later version.

Repository: https://github.com/kpu/kenlm

Usage in Nacre: N-gram language model scoring for conversion candidate ranking.

---

## zenz - Neural Kana-Kanji Conversion Model (user-downloaded, not bundled)

Model: zenz-v3.2-small (GGUF, Q5_K_M)
Author: Miwa-Keita (Zenzai project)
License: CC-BY-SA-4.0 (Creative Commons Attribution-ShareAlike 4.0 International)

Base model: ku-nlp/gpt2-small-japanese-char — a character-level Japanese
GPT-2 from Kyoto University (also CC-BY-SA-4.0), fine-tuned for kana-kanji
conversion.

Source: https://huggingface.co/Miwa-Keita/zenz-v3.2-small-gguf
Project: https://github.com/Miwa-Keita/zenz (Zenzai)

Usage in Nacre: Neural full-sentence kana-kanji conversion. When the user
invokes conversion, zenz generates a candidate that is shown first, ahead of
the built-in dictionary engine's candidates (hybrid conversion). The model
file is downloaded by the user at runtime (Settings → AI Models, or the
first-run auto-provisioning); it is NOT distributed inside the APK. This
attribution is also shown in the Settings AI Models section.
