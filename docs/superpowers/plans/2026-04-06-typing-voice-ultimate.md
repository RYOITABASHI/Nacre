# Typing & Voice Ultimate Improvement — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Achieve ATOK-level typing conversion and Typeless-equivalent voice input by refactoring the conversion engine, bundling KenLM 3-gram, and enhancing both typing and voice pipelines.

**Architecture:** Bottom-up approach: split NacreDictionary.kt (2,267 lines) into 6 focused files, bundle a 3-gram KenLM model in APK for all-user LM access, improve Viterbi with clause segmentation and dynamic beam, enhance user learning with time decay and domain separation, upgrade voice pipeline with streaming and always-on mode.

**Tech Stack:** Kotlin, Jetpack Compose, KenLM (C++ JNI), sherpa-onnx (SenseVoice), Mozc OSS dictionary, Silero VAD

**Spec:** `docs/superpowers/specs/2026-04-06-typing-voice-ultimate-design.md`

---

## File Map

### New Files (Phase 1 — Refactoring)

| File | Responsibility |
|---|---|
| `ime-core/.../input/DictionaryManager.kt` | Dictionary loading, entry lookup, prefix search, connection cost matrix |
| `ime-core/.../input/ViterbiEngine.kt` | Beam search, path construction, cost calculation |
| `ime-core/.../input/UserLearner.kt` | Boost calculation, time decay, persistence, rejection learning |
| `ime-core/.../input/CandidateRanker.kt` | KenLM rescoring, POS context cost, LLM rerank integration |
| `ime-core/.../input/EnglishMatcher.kt` | English dict, prediction, spell correction, bigram learning |
| `ime-core/.../input/ConversionPipeline.kt` | Public API (DictionaryProvider impl), orchestrates all components |

### New Test Files

| File | Tests |
|---|---|
| `ime-core/src/test/.../input/DictionaryManagerTest.kt` | Connection cost lookup, dict entry search |
| `ime-core/src/test/.../input/ViterbiEngineTest.kt` | Segmentation, beam width, time budget |
| `ime-core/src/test/.../input/UserLearnerTest.kt` | Decay, domain, rejection, persistence |
| `ime-core/src/test/.../input/CandidateRankerTest.kt` | KenLM weight branching, score normalization |
| `ime-core/src/test/.../input/ConversionPipelineTest.kt` | Snapshot regression tests |

### Modified Files

| File | Changes |
|---|---|
| `ime-core/.../input/InputEngine.kt:1123-1129` | Add `predictNextWord()` to DictionaryProvider interface |
| `ime-core/.../input/InputEngine.kt:772,793` | Remove `as? NacreDictionary` casts |
| `ime-ai/.../KenLmScorer.kt` | Add `selectModel()`, `getOrder()` wrapper |
| `ime-core/.../input/VoiceInputManager.kt` | Streaming, always-on, SenseVoice chunk stability |
| `ime-ai/.../PostProcessor.kt` | KenLM punctuation, context filler, correction learning |
| `ime-ai/.../LlmPostProcessor.kt` | Tech term expansion, number processing |
| `ime-core/.../keyboard/CandidateBar.kt` | Long-press inline dict registration |
| `ime-core/.../input/LlmReranker.kt` | Integration with CandidateRanker constructor |
| `.github/workflows/train-kenlm.yml` | Add 3-gram training job |

All paths below use the prefix `ime-core/src/main/kotlin/space/manus/nacre/ime/input/` (abbreviated as `input/`).

---

## Phase 1: NacreDictionary Refactoring

### Task 0: Set up ime-core test infrastructure

**Files:**
- Modify: `ime-core/build.gradle.kts`
- Create: `ime-core/src/androidTest/kotlin/space/manus/nacre/ime/input/` (directory)

**Note:** `ime-core/src/test/` does not exist. Since conversion tests need Android Context (for loading Mozc dict assets), all tests must be instrumented tests under `src/androidTest/`.

- [ ] **Step 1: Add test dependencies to ime-core/build.gradle.kts**

```kotlin
dependencies {
    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:core:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
}
```

- [ ] **Step 2: Create androidTest directory structure**

```bash
mkdir -p ime-core/src/androidTest/kotlin/space/manus/nacre/ime/input/
```

- [ ] **Step 3: Verify build**

Run: `cd ~/Nacre && ./gradlew :ime-core:assembleDebugAndroidTest 2>&1 | tail -10`

- [ ] **Step 4: Commit**

```bash
git add ime-core/build.gradle.kts ime-core/src/androidTest/
git commit -m "chore: set up ime-core androidTest infrastructure"
```

---

### Task 1: Snapshot regression test baseline

**Files:**
- Create: `ime-core/src/androidTest/kotlin/space/manus/nacre/ime/input/ConversionSnapshotTest.kt`
- Read: `ime-core/src/main/kotlin/space/manus/nacre/ime/input/NacreDictionary.kt`

**Note:** Uses instrumented tests (`androidTest`) because dictionary loading requires Android Context for asset access.

- [ ] **Step 1: Create snapshot test file**

Write a test that calls `NacreDictionary.convert()` and `predict()` with 50+ representative inputs and records the top-5 candidates for each. Inputs should cover: common words, long phrases, ambiguous readings, katakana loanwords, particles, proper nouns, English-mixed.

```kotlin
@RunWith(AndroidJUnit4::class)
class ConversionSnapshotTest {
    companion object {
        val SNAPSHOT_INPUTS = listOf(
            "こんにちは", "おせわになっております", "とうきょうとっきょきょかきょく",
            "きょうはいいてんきですね", "にほんにいきます", "ぷろぐらみんぐげんご",
            "はしをわたる", "かれはがくせいです", "おおさかのたこやき",
            "しんかんせんでとうきょうへ", "あしたのかいぎ", "これはてすとです",
            "でんわばんごう", "さんぜんえん", "じゅういちがつみっか",
            // ... 35+ more covering edge cases
        )
    }

    @Test
    fun snapshotConvert() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dict = NacreDictionary(context)
        dict.load()
        for (input in SNAPSHOT_INPUTS) {
            val result = dict.convert(input)
            // Record: println("$input\t${result.take(5).joinToString("|") { it.surface }}")
            // After baseline captured, assert against saved snapshots
        }
    }
}
```

- [ ] **Step 2: Run test to generate baseline**

Run: `cd ~/Nacre && ./gradlew :ime-core:connectedAndroidTest --tests "*.ConversionSnapshotTest" -i 2>&1 | tail -20`

Capture output as baseline snapshot.

- [ ] **Step 3: Save baseline to file**

Save output to `ime-core/src/androidTest/assets/conversion_snapshots.tsv` (input\tcandidate1|candidate2|...).

- [ ] **Step 4: Commit**

```bash
git add ime-core/src/androidTest/
git commit -m "test: add conversion snapshot baseline for refactoring regression"
```

---

### Task 2: Extract DictionaryManager

**Files:**
- Create: `ime-core/src/main/kotlin/space/manus/nacre/ime/input/DictionaryManager.kt`
- Modify: `ime-core/src/main/kotlin/space/manus/nacre/ime/input/NacreDictionary.kt`

- [ ] **Step 1: Create DictionaryManager.kt**

Extract from NacreDictionary.kt lines 100-348 (data classes, load methods, connection cost):

```kotlin
package space.manus.nacre.ime.input

import android.content.Context

data class DictEntry(val surface: String, val cost: Int, val leftGroup: Int = 1, val rightGroup: Int = 1)
data class UserDictEntry(val reading: String, val surface: String, val comment: String = "")
data class PhraseEntry(val reading: String, val surface: String, val count: Int = 1, val lastEpoch: Int = 0)

class DictionaryManager(private val context: Context) {
    // Dict storage — HashMap with capacity hint, matching NacreDictionary line 31
    // Dict is loaded once at startup and read-only thereafter; no need for ConcurrentHashMap
    val dict = HashMap<String, MutableList<DictEntry>>(400000)
    // Sorted readings array for binary search — must be Array<String>, not List
    // (matches NacreDictionary line 34: var sortedReadings: Array<String> = emptyArray())
    var sortedReadings: Array<String> = emptyArray()
    var entryCount = 0  // for debug display (line 97)

    // Connection cost matrix
    var connectionCostFlat: ShortArray = ShortArray(0)
    var numIds: Int = 0

    // POS constants (from NacreDictionary companion object lines 1628-1668)
    companion object {
        const val DEFAULT_CONNECTION_COST = 2000
        fun isNoun(id: Int) = id in 1841..2193
        fun isVerb(id: Int) = id in 434..1840
        fun isParticle(id: Int) = id in 268..433
        fun isAuxVerb(id: Int) = id in 29..267
        fun isAdjective(id: Int) = id in 2194..2588
        fun isContentWord(id: Int) = id in 434..2588
        fun isFunctionWord(id: Int) = id in 29..433
        fun isAdverb(id: Int) = id in 12..28
        fun isConjunction(id: Int) = id in 2591..2593
        fun isSymbol(id: Int) = id in 2641..2656
    }

    fun load() { /* move load() lines 120-171 */ }
    fun loadConnectionMatrix() { /* lines 173-202 */ }
    fun loadConnectionMatrixTsvFallback() { /* lines 204-235 */ }
    fun loadMozcDictionary() { /* lines 237-282 */ }
    fun loadSlangDictionary() { /* lines 283-317 */ }
    // Actual signature: loadSupplementaryDict(assetPath: String, label: String) — line 318
    fun loadSupplementaryDict(assetPath: String, label: String) { /* lines 318-348 */ }
    fun loadStaticBigrams(): Map<String, List<Pair<String, Int>>> { /* lines 1165-1194 */ }

    fun getConnectionCost(prevRightId: Int, currLeftId: Int): Int {
        if (prevRightId < 0 || currLeftId < 0 || prevRightId >= numIds || currLeftId >= numIds)
            return DEFAULT_CONNECTION_COST
        return connectionCostFlat[prevRightId * numIds + currLeftId].toInt() / 3
    }

    fun lookup(reading: String): List<DictEntry> = dictMap[reading] ?: emptyList()

    fun prefixSearch(prefix: String, limit: Int = 100): List<Pair<String, List<DictEntry>>> {
        /* Binary search on sortedReadings, lines 1057-1110 */
    }
}
```

- [ ] **Step 2: Update NacreDictionary to delegate to DictionaryManager**

Replace direct dict/connection access in NacreDictionary with calls to `DictionaryManager`. Keep NacreDictionary as a thin wrapper for now — it will be fully replaced later.

- [ ] **Step 3: Run snapshot test**

Run: `cd ~/Nacre && ./gradlew :ime-core:test --tests "*.ConversionSnapshotTest"`
Expected: All snapshots match baseline (no regression).

- [ ] **Step 4: Commit**

```bash
git add ime-core/src/main/kotlin/space/manus/nacre/ime/input/DictionaryManager.kt
git add ime-core/src/main/kotlin/space/manus/nacre/ime/input/NacreDictionary.kt
git commit -m "refactor: extract DictionaryManager from NacreDictionary"
```

---

### Task 3: Extract ViterbiEngine

**Files:**
- Create: `ime-core/src/main/kotlin/space/manus/nacre/ime/input/ViterbiEngine.kt`
- Modify: `ime-core/src/main/kotlin/space/manus/nacre/ime/input/NacreDictionary.kt`

- [ ] **Step 1: Create ViterbiEngine.kt**

Extract from NacreDictionary.kt:
- `viterbiConvert()` (lines 1686-1890)
- `lengthBonus()` (lines 1670-1684)
- `hiraganaToKatakana()` (lines 1955-1962)
- `toHalfWidthKatakana()` (lines 1964-1977)
- `estimateKatakanaCost()` (lines 1897-1921)
- `isLikelyLoanword()` (lines 1927-1953)
- `generateKanaVariants()` (lines 989-1022)
- `exactMatch()` (lines 1026-1055)
- Partial segmentation logic from `convert()` (lines 381-438)

```kotlin
class ViterbiEngine(
    private val dictManager: DictionaryManager,
    private val kenLmScorer: KenLmScorer? = null
) {
    fun search(kana: String, beamWidth: Int, lmWeight: Float): List<ViterbiPath> { /* ... */ }
    fun exactMatch(kana: String): List<ConversionCandidate> { /* ... */ }
    fun partialSegmentation(kana: String): List<ConversionCandidate> { /* ... */ }
    fun generateKanaVariants(kana: String): List<String> { /* ... */ }
    // ... helper methods
}
```

ViterbiEngine takes `DictionaryManager` and optional `KenLmScorer` as constructor params.

- [ ] **Step 2: Update NacreDictionary to delegate Viterbi calls**

- [ ] **Step 3: Run snapshot test**

Expected: All snapshots match.

- [ ] **Step 4: Commit**

```bash
git add ime-core/src/main/kotlin/space/manus/nacre/ime/input/ViterbiEngine.kt
git add ime-core/src/main/kotlin/space/manus/nacre/ime/input/NacreDictionary.kt
git commit -m "refactor: extract ViterbiEngine from NacreDictionary"
```

---

### Task 4: Extract UserLearner

**Files:**
- Create: `ime-core/src/main/kotlin/space/manus/nacre/ime/input/UserLearner.kt`
- Modify: `ime-core/src/main/kotlin/space/manus/nacre/ime/input/NacreDictionary.kt`

- [ ] **Step 1: Create UserLearner.kt**

Extract from NacreDictionary.kt:
- `userBoost`, `bigramBoost`, `trigramBoost` maps
- `recentHistory` list
- `userDictionary`, `phraseMemory` maps
- `applyBoost()` (lines 864-916)
- `recordSelection()` (lines 609-640)
- `loadUserBoost()` / `saveUserBoost()` (lines 1982-2067)
- `loadUserDictionary()` / `saveUserDictionary()` (lines 2071-2153)
- `loadPhraseMemory()` / `savePhraseMemory()` (lines 2154-2193)
- `recordPhrase()` (lines 2194-2257)
- `registerUserWord()` (around line 2129)

```kotlin
class UserLearner(private val context: Context) {
    private val userBoost = ConcurrentHashMap<String, Int>()
    private val bigramBoost = ConcurrentHashMap<String, Int>()
    private val trigramBoost = ConcurrentHashMap<String, Int>()
    // ...

    fun getBoost(reading: String, surface: String, prevSurface: String?, prev2Surface: String?): Int { /* ... */ }
    fun recordSelection(candidate: ConversionCandidate) { /* ... */ }
    fun save() { /* ... */ }
    fun load() { /* ... */ }
}
```

- [ ] **Step 2: Update NacreDictionary to delegate learning calls**

- [ ] **Step 3: Run snapshot test**

Expected: All snapshots match.

- [ ] **Step 4: Commit**

```bash
git add ime-core/src/main/kotlin/space/manus/nacre/ime/input/UserLearner.kt
git add ime-core/src/main/kotlin/space/manus/nacre/ime/input/NacreDictionary.kt
git commit -m "refactor: extract UserLearner from NacreDictionary"
```

---

### Task 5: Extract CandidateRanker

**Files:**
- Create: `ime-core/src/main/kotlin/space/manus/nacre/ime/input/CandidateRanker.kt`
- Modify: `ime-core/src/main/kotlin/space/manus/nacre/ime/input/NacreDictionary.kt`

- [ ] **Step 1: Create CandidateRanker.kt**

Extract from NacreDictionary.kt:
- `kenLmRescore()` (lines 928-974)
- `posContextCost()` (line 908)
- LM weight constants: `VITERBI_LM_WEIGHT`, `KENLM_WEIGHT`
- Candidate sorting and filtering logic from `convert()` (lines 459-474)

```kotlin
class CandidateRanker(
    private val kenLmScorer: KenLmScorer?,
    private val llmReranker: LlmReranker?,
    private val userLearner: UserLearner
) {
    // Weight configuration
    var viterbiLmWeight = 3000f
    var rescoreWeight = 2500f
    var contextMultiplier = 1.15f

    fun configureWeights(lmOrder: Int) {
        if (lmOrder <= 3) {
            viterbiLmWeight = 2200f; rescoreWeight = 2000f; contextMultiplier = 1.10f
        } else {
            viterbiLmWeight = 3000f; rescoreWeight = 2500f; contextMultiplier = 1.25f
        }
    }

    fun rank(candidates: List<ConversionCandidate>, reading: String, context: List<String>): List<ConversionCandidate> { /* ... */ }
    fun kenLmRescore(candidates: List<ConversionCandidate>, reading: String, context: List<String>): List<ConversionCandidate> { /* ... */ }
}
```

- [ ] **Step 2: Update NacreDictionary to delegate ranking**

- [ ] **Step 3: Run snapshot test**

Expected: All snapshots match.

- [ ] **Step 4: Commit**

```bash
git add ime-core/src/main/kotlin/space/manus/nacre/ime/input/CandidateRanker.kt
git add ime-core/src/main/kotlin/space/manus/nacre/ime/input/NacreDictionary.kt
git commit -m "refactor: extract CandidateRanker from NacreDictionary"
```

---

### Task 6: Extract EnglishMatcher

**Files:**
- Create: `ime-core/src/main/kotlin/space/manus/nacre/ime/input/EnglishMatcher.kt`
- Modify: `ime-core/src/main/kotlin/space/manus/nacre/ime/input/NacreDictionary.kt`

- [ ] **Step 1: Create EnglishMatcher.kt**

Extract from NacreDictionary.kt:
- `loadEnglishDict()` (lines 1114-1136)
- `loadEnglishFullDict()` (lines 1137-1164)
- `predictEnglish()` (lines 1195-1254)
- `recordEnglishSelection()` (lines 1259-1268)
- `englishMatch()` (lines 1355-1627) — spell correction, edit distance
- `buildRomajiEnglishIndex()` and related methods
- English data structures: `englishDict`, `englishFullDict`, `englishSortedKeys`, `englishBigramBoost`

```kotlin
class EnglishMatcher(private val context: Context) {
    fun load() { /* ... */ }
    fun predict(prefix: String, limit: Int = 20): List<ConversionCandidate> { /* ... */ }
    fun match(hiragana: String): List<ConversionCandidate> { /* ... */ }
    fun recordSelection(word: String) { /* ... */ }
    fun spellCorrect(word: String, limit: Int = 5): List<String> { /* ... */ }
}
```

- [ ] **Step 2: Update NacreDictionary to delegate English calls**

- [ ] **Step 3: Run snapshot test**

Expected: All snapshots match.

- [ ] **Step 4: Commit**

```bash
git add ime-core/src/main/kotlin/space/manus/nacre/ime/input/EnglishMatcher.kt
git add ime-core/src/main/kotlin/space/manus/nacre/ime/input/NacreDictionary.kt
git commit -m "refactor: extract EnglishMatcher from NacreDictionary"
```

---

### Task 7a: Create ConversionPipeline and update DictionaryProvider

**Files:**
- Create: `ime-core/src/main/kotlin/space/manus/nacre/ime/input/ConversionPipeline.kt`
- Modify: `ime-core/src/main/kotlin/space/manus/nacre/ime/input/InputEngine.kt:1123-1129` (DictionaryProvider interface)

- [ ] **Step 1: Add `predictNextWord()` and `updateContext()` to DictionaryProvider**

In `InputEngine.kt` line 1123-1129, add:

```kotlin
interface DictionaryProvider {
    fun convert(kana: String): List<ConversionCandidate>
    fun predict(kana: String, romaji: String = ""): List<ConversionCandidate>
    fun recordSelection(candidate: ConversionCandidate)
    fun predictEnglish(prefix: String, limit: Int = 20): List<ConversionCandidate>
    fun recordEnglishSelection(word: String)
    fun predictNextWord(limit: Int = 8): List<ConversionCandidate>  // NEW
    fun updateContext(kana: String)  // NEW — was only on NacreDictionary (line 642)
}
```

- [ ] **Step 2: Create ConversionPipeline.kt**

```kotlin
class ConversionPipeline(
    private val dictManager: DictionaryManager,
    private val viterbiEngine: ViterbiEngine,
    private val userLearner: UserLearner,
    private val candidateRanker: CandidateRanker,
    private val englishMatcher: EnglishMatcher
) : DictionaryProvider {

    override fun convert(kana: String): List<ConversionCandidate> {
        // Orchestrate: exact match + Viterbi + variants + boost + rank
        // Move logic from NacreDictionary.convert() lines 349-475
    }

    override fun predict(kana: String, romaji: String): List<ConversionCandidate> {
        // Move logic from NacreDictionary.predict() lines 477-607
    }

    override fun recordSelection(candidate: ConversionCandidate) {
        userLearner.recordSelection(candidate)
    }

    override fun predictEnglish(prefix: String, limit: Int) = englishMatcher.predict(prefix, limit)
    override fun recordEnglishSelection(word: String) = englishMatcher.recordSelection(word)

    override fun predictNextWord(limit: Int): List<ConversionCandidate> {
        // Move logic from NacreDictionary.predictNextWord() lines 653-747
    }

    override fun updateContext(kana: String) {
        // Delegates to UserLearner — updates recentHistory and bigram/trigram context
        // Move logic from NacreDictionary.updateContext() line 642
        userLearner.updateContext(kana)
    }
}
```

- [ ] **Step 3: Run snapshot test**

Expected: All snapshots match.

- [ ] **Step 4: Commit**

```bash
git add ime-core/src/main/kotlin/space/manus/nacre/ime/input/ConversionPipeline.kt
git add ime-core/src/main/kotlin/space/manus/nacre/ime/input/InputEngine.kt
git commit -m "refactor: create ConversionPipeline, add predictNextWord/updateContext to DictionaryProvider"
```

---

### Task 7b: Rewire InputEngine and delete NacreDictionary

**Files:**
- Modify: `ime-core/src/main/kotlin/space/manus/nacre/ime/input/InputEngine.kt:772,793` (remove casts)
- Delete: `ime-core/src/main/kotlin/space/manus/nacre/ime/input/NacreDictionary.kt`

- [ ] **Step 1: Remove `as? NacreDictionary` casts in InputEngine.kt**

At line 772: Replace `val nacrDict = dictionary as? NacreDictionary ?: return` with `dictionary?.predictNextWord(limit = 8)`.
At line 793: Replace `(dictionary as? NacreDictionary)?.updateContext(kana)` with `dictionary?.updateContext(kana)`.

- [ ] **Step 2: Update InputEngine to instantiate ConversionPipeline instead of NacreDictionary**

Find where NacreDictionary is constructed and replace with ConversionPipeline construction (wiring all 5 components).

- [ ] **Step 3: Run snapshot test**

Expected: All snapshots match.

- [ ] **Step 4: Delete NacreDictionary.kt**

After verifying all tests pass, remove the now-empty `NacreDictionary.kt`.

- [ ] **Step 5: Run full build**

Run: `cd ~/Nacre && ./gradlew :ime-core:assembleDebug 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add ime-core/src/main/kotlin/space/manus/nacre/ime/input/InputEngine.kt
git rm ime-core/src/main/kotlin/space/manus/nacre/ime/input/NacreDictionary.kt
git commit -m "refactor: rewire InputEngine to ConversionPipeline, delete NacreDictionary

Remove as? NacreDictionary casts, use DictionaryProvider interface.
Delete NacreDictionary.kt (2,267 lines → 6 focused files)."
```

---

## Phase 2: KenLM 3-gram Bundle

### Task 8: Extend KenLM training pipeline for 3-gram

**Files:**
- Modify: `.github/workflows/train-kenlm.yml`

- [ ] **Step 1: Add 3-gram training job**

Add a job `train-3gram` to the existing workflow:

```yaml
  train-3gram:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Install KenLM
        run: |
          sudo apt-get install -y libboost-all-dev cmake build-essential
          git clone https://github.com/kpu/kenlm.git
          cd kenlm && mkdir build && cd build
          cmake .. && make -j$(nproc)
      - name: Download and prepare corpus
        run: |
          # Use same Wikipedia corpus as 5-gram
          wget -q "$CORPUS_URL" -O corpus.txt
      - name: Train 3-gram with pruning
        run: |
          kenlm/build/bin/lmplz -o 3 --prune 0 2 3 < corpus.txt > japanese-3gram.arpa
          kenlm/build/bin/build_binary -s trie japanese-3gram.arpa japanese-3gram.klm
          ls -lh japanese-3gram.klm
      - name: Upload artifact
        uses: actions/upload-artifact@v4
        with:
          name: kenlm-3gram
          path: japanese-3gram.klm
```

- [ ] **Step 2: Commit**

```bash
git add .github/workflows/train-kenlm.yml
git commit -m "ci: add KenLM 3-gram training job with pruning"
```

---

### Task 9: Add model selection to KenLmScorer

**Files:**
- Modify: `ime-ai/src/main/kotlin/space/manus/nacre/ai/KenLmScorer.kt`
- Create: `ime-ai/src/test/kotlin/space/manus/nacre/ai/KenLmScorerTest.kt`

- [ ] **Step 1: Write test for selectModel()**

```kotlin
class KenLmScorerTest {
    @Test
    fun selectModel_prefers5gramOverBundled() {
        val filesDir = createTempDir()
        val extDir = createTempDir()
        File(filesDir, "models").mkdirs()
        File(extDir, "models").mkdirs()

        // Create both model files
        File(filesDir, "models/japanese-3gram.klm").createNewFile()
        File(extDir, "models/japanese-5gram.klm").createNewFile()

        val result = KenLmScorer.selectModel(filesDir, listOf(extDir))
        assertTrue(result!!.contains("5gram"))
    }

    @Test
    fun selectModel_fallsBackTo3gram() {
        val filesDir = createTempDir()
        File(filesDir, "models").mkdirs()
        File(filesDir, "models/japanese-3gram.klm").createNewFile()

        val result = KenLmScorer.selectModel(filesDir, emptyList())
        assertTrue(result!!.contains("3gram"))
    }

    @Test
    fun selectModel_returnsNullWhenNoModel() {
        val filesDir = createTempDir()
        val result = KenLmScorer.selectModel(filesDir, emptyList())
        assertNull(result)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd ~/Nacre && ./gradlew :ime-ai:test --tests "*.KenLmScorerTest" -i 2>&1 | tail -10`
Expected: FAIL (selectModel not defined)

- [ ] **Step 3: Implement selectModel()**

Add to `KenLmScorer.kt`:

```kotlin
companion object {
    fun selectModel(filesDir: File, externalDirs: List<File>): String? {
        // 1. External 5-gram (highest priority)
        for (dir in externalDirs) {
            val path = File(dir, "models/japanese-5gram.klm")
            if (path.exists() && path.length() > 0) return path.absolutePath
        }
        // 2. Quick paths for 5-gram
        listOf("/sdcard/Download", "/sdcard/models").forEach { p ->
            val f = File(p, "japanese-5gram.klm")
            if (f.exists() && f.length() > 0) return f.absolutePath
        }
        // 3. Bundled 3-gram
        val bundled = File(filesDir, "models/japanese-3gram.klm")
        if (bundled.exists() && bundled.length() > 0) return bundled.absolutePath
        return null
    }
}

fun getModelOrder(): Int {
    return try { KenLmJni.getOrder() } catch (_: Exception) { 3 }
}
```

- [ ] **Step 4: Run test to verify it passes**

- [ ] **Step 5: Commit**

```bash
git add ime-ai/src/main/kotlin/space/manus/nacre/ai/KenLmScorer.kt
git add ime-ai/src/test/kotlin/space/manus/nacre/ai/KenLmScorerTest.kt
git commit -m "feat: add KenLM model selection with 5-gram priority and 3-gram fallback"
```

---

### Task 10: Bundle 3-gram model and asset extraction

**Files:**
- Create: `ime-ai/src/main/assets/models/` (directory — model file placed by CI)
- Modify: `ime-ai/src/main/kotlin/space/manus/nacre/ai/ModelDownloader.kt`

- [ ] **Step 1: Add bundled model extraction to ModelDownloader**

Add method to extract assets/models/japanese-3gram.klm → filesDir/models/ on first launch:

```kotlin
fun extractBundledKenLm(context: Context): String? {
    val targetDir = File(context.filesDir, "models")
    targetDir.mkdirs()
    val target = File(targetDir, "japanese-3gram.klm")
    if (target.exists() && target.length() > 1_000_000) return target.absolutePath

    return try {
        context.assets.open("models/japanese-3gram.klm").use { input ->
            target.outputStream().use { output ->
                input.copyTo(output, bufferSize = 65536)
            }
        }
        if (target.exists()) target.absolutePath else null
    } catch (e: Exception) {
        Log.w("ModelDownloader", "Failed to extract bundled KenLM: ${e.message}")
        null
    }
}
```

- [ ] **Step 2: Integrate extraction into IME service startup**

Call `extractBundledKenLm()` early in the IME initialization (before `KenLmScorer.selectModel()`).

- [ ] **Step 3: Commit**

```bash
git add ime-ai/src/main/kotlin/space/manus/nacre/ai/ModelDownloader.kt
git commit -m "feat: extract bundled KenLM 3-gram from assets on first launch"
```

---

### Task 11: Configure CandidateRanker weight branching

**Files:**
- Modify: `ime-core/src/main/kotlin/space/manus/nacre/ime/input/CandidateRanker.kt`
- Create: `ime-core/src/test/kotlin/space/manus/nacre/ime/input/CandidateRankerTest.kt`

- [ ] **Step 1: Write test for weight configuration**

```kotlin
class CandidateRankerTest {
    @Test
    fun configureWeights_3gram() {
        val ranker = CandidateRanker(null, null, mockUserLearner())
        ranker.configureWeights(3)
        assertEquals(2200f, ranker.viterbiLmWeight)
    }

    @Test
    fun configureWeights_5gram() {
        val ranker = CandidateRanker(null, null, mockUserLearner())
        ranker.configureWeights(5)
        assertEquals(3000f, ranker.viterbiLmWeight)
    }
}
```

- [ ] **Step 2: Implement configureWeights() in CandidateRanker**

Per spec section 2.5.

- [ ] **Step 3: Run tests, commit**

```bash
git add ime-core/src/main/kotlin/space/manus/nacre/ime/input/CandidateRanker.kt
git add ime-core/src/test/kotlin/space/manus/nacre/ime/input/CandidateRankerTest.kt
git commit -m "feat: add KenLM order-based weight branching to CandidateRanker"
```

---

## Phase 3: Viterbi Improvements

### Task 12: Dynamic beam width

**Files:**
- Modify: `ime-core/src/main/kotlin/space/manus/nacre/ime/input/ViterbiEngine.kt`
- Create: `ime-core/src/test/kotlin/space/manus/nacre/ime/input/ViterbiEngineTest.kt`

- [ ] **Step 1: Write test**

```kotlin
class ViterbiEngineTest {
    @Test
    fun dynamicBeamWidth_shortInput_highAmbiguity() {
        val width = ViterbiEngine.dynamicBeamWidth(4, ambiguity = 1.0f, hasLM = true)
        assertEquals(60, width) // base 40, ambiguity 1.0 -> 40 * 1.5 = 60, capped at 60
    }

    @Test
    fun dynamicBeamWidth_longInput_lowAmbiguity() {
        val width = ViterbiEngine.dynamicBeamWidth(15, ambiguity = 0.2f, hasLM = true)
        assertEquals(33, width) // base 30, * 1.1 = 33
    }
}
```

- [ ] **Step 2: Implement dynamicBeamWidth()**

Per spec section 3.1. Replace static beam width in `search()`.

- [ ] **Step 3: Add time budget**

Per spec section 3.4. Add `System.nanoTime()` check inside Viterbi loop with 50ms default.

- [ ] **Step 4: Run tests, commit**

```bash
git commit -m "feat: add dynamic beam width and time budget to ViterbiEngine"
```

---

### Task 13: Two-pass clause segmentation

**Files:**
- Modify: `ime-core/src/main/kotlin/space/manus/nacre/ime/input/ViterbiEngine.kt`
- Modify: `ime-core/src/test/kotlin/space/manus/nacre/ime/input/ViterbiEngineTest.kt`

- [ ] **Step 1: Write test for clause segmentation**

```kotlin
@Test
fun clauseSegmentation_longInput_splitsAtParticles() {
    // "にほんにいきますか" (12 chars) → should segment at particle "に" after "日本"
    val engine = createTestViterbiEngine()
    val result = engine.convertWithClauseSegmentation("にほんにいきますか")
    val surfaces = result.map { it.surface }.joinToString("")
    assertTrue(surfaces.contains("日本"))
    assertTrue(surfaces.contains("行きます"))
}

@Test
fun clauseSegmentation_shortInput_skipsPass2() {
    // Under 12 chars → single-pass Viterbi only
    val engine = createTestViterbiEngine()
    val result = engine.convertWithClauseSegmentation("こんにちは")
    assertFalse(result.isEmpty())
}
```

- [ ] **Step 2: Implement two-pass clause segmentation**

Per spec section 3.2:
- Pass 1: K=10 lightweight Viterbi for POS estimation
- Pass 2: Split at detected particle boundaries (POS 268-433), run K=40 per clause
- Fallback: Use Pass 1 result if Pass 2 total cost is worse

- [ ] **Step 3: Run tests, commit**

```bash
git commit -m "feat: add two-pass clause segmentation for long text conversion"
```

---

### Task 14: Backward KenLM rescoring

**Files:**
- Modify: `ime-core/src/main/kotlin/space/manus/nacre/ime/input/CandidateRanker.kt`
- Modify: `ime-core/src/test/kotlin/space/manus/nacre/ime/input/CandidateRankerTest.kt`

- [ ] **Step 1: Write test**

```kotlin
@Test
fun backwardKenLmRescore_disambiguatesHomophones() {
    // "はしをわたる" → "橋を渡る" should rank higher than "箸を渡る"
    // when backward KenLM sees "渡る" context
    // (This test requires KenLM model - may be an integration test)
}
```

- [ ] **Step 2: Implement backward rescoring in CandidateRanker**

Per spec section 3.3. Score reversed token sequences, combine with forward cost at weight 0.3.

- [ ] **Step 3: Run tests, commit**

```bash
git commit -m "feat: add backward KenLM rescoring for homophone disambiguation"
```

---

### Task 15: Dynamic cost calculation improvements

**Files:**
- Modify: `ime-core/src/main/kotlin/space/manus/nacre/ime/input/ViterbiEngine.kt`
- Modify: `ime-core/src/main/kotlin/space/manus/nacre/ime/input/DictionaryManager.kt`

- [ ] **Step 1: Implement dynamic connection cost scale**

In `DictionaryManager.getConnectionCost()`:
- Short text (≤5 chars): ÷4
- Medium (6-10): ÷3 (current)
- Long (11+): ÷2.5

Add `inputLength` parameter.

- [ ] **Step 2: Implement KenLM×length bonus interaction**

In `ViterbiEngine.search()`: If LM score is in top 25% of candidates, amplify length bonus by ×1.3.

- [ ] **Step 3: Implement dynamic hiragana function word bonus**

In `ViterbiEngine.search()`: Prev POS = noun + current = 「の」 → -3000; prev POS = verb + current = 「の」 → -1500.

- [ ] **Step 4: Run snapshot test for regression**

- [ ] **Step 5: Commit**

```bash
git commit -m "feat: dynamic connection cost scaling and POS-aware function word bonus"
```

---

## Phase 4: User Learning Enhancement

### Task 16: Time decay for user boost

**Files:**
- Modify: `ime-core/src/main/kotlin/space/manus/nacre/ime/input/UserLearner.kt`
- Create: `ime-core/src/test/kotlin/space/manus/nacre/ime/input/UserLearnerTest.kt`

- [ ] **Step 1: Write tests**

```kotlin
class UserLearnerTest {
    @Test
    fun decayedBoost_recentEntry_fullBoost() {
        val entry = BoostEntry(count = 3, lastUsedAt = System.currentTimeMillis(), sessionCount = 1)
        val boost = UserLearner.decayedBoost(entry, System.currentTimeMillis())
        assertEquals(4500, boost) // 3 selections = 4500 raw boost, decay 1.0
    }

    @Test
    fun decayedBoost_14dayOld_60percent() {
        val twoWeeksAgo = System.currentTimeMillis() - 14 * 24 * 60 * 60 * 1000L
        val entry = BoostEntry(count = 3, lastUsedAt = twoWeeksAgo, sessionCount = 1)
        val boost = UserLearner.decayedBoost(entry, System.currentTimeMillis())
        assertEquals(2700, boost) // 4500 * 0.6
    }

    @Test
    fun decayedBoost_31dayOld_15percent() {
        val monthAgo = System.currentTimeMillis() - 31 * 24 * 60 * 60 * 1000L
        val entry = BoostEntry(count = 3, lastUsedAt = monthAgo, sessionCount = 1)
        val boost = UserLearner.decayedBoost(entry, System.currentTimeMillis())
        assertEquals(675, boost) // 4500 * 0.15
    }
}
```

- [ ] **Step 2: Implement BoostEntry data class and decayedBoost()**

Per spec section 4.1. Update `getBoost()` to use `decayedBoost()`.

- [ ] **Step 3: Update save/load to persist lastUsedAt timestamps**

Change SharedPreferences format from `reading:surface\tcount` to `reading:surface\tcount\ttimestamp`.

- [ ] **Step 4: Add session cleanup (delete 30+ day entries on startup)**

- [ ] **Step 5: Run tests, commit**

```bash
git commit -m "feat: add time-based decay to user learning boost"
```

---

### Task 17: Rejection learning

**Files:**
- Modify: `ime-core/src/main/kotlin/space/manus/nacre/ime/input/UserLearner.kt`
- Modify: `ime-core/src/test/kotlin/space/manus/nacre/ime/input/UserLearnerTest.kt`

- [ ] **Step 1: Write tests**

```kotlin
@Test
fun rejection_3times_appliesPenalty() {
    val learner = createTestLearner()
    repeat(3) { learner.recordRejection("test:テスト", 500) }
    val penalty = learner.getRejectionPenalty("test:テスト")
    assertEquals(1500, penalty) // 3 * 500, capped at 2000
}

@Test
fun rejection_positiveSelection_resets() {
    val learner = createTestLearner()
    repeat(3) { learner.recordRejection("test:テスト", 500) }
    learner.onPositiveSelection("test:テスト")
    val penalty = learner.getRejectionPenalty("test:テスト")
    assertEquals(0, penalty)
}
```

- [ ] **Step 2: Implement rejection learning**

Per spec section 4.5. Add `rejectCount`, `rejectionPenalty` maps with time decay.

- [ ] **Step 3: Implement correction detection**

Per spec section 4.6. Track `lastCommit`/`lastCommitTime` in UserLearner, detect BS within 500ms.

- [ ] **Step 4: Run tests, commit**

```bash
git commit -m "feat: add rejection learning and correction detection"
```

---

### Task 18: Domain-specific learning

**Files:**
- Modify: `ime-core/src/main/kotlin/space/manus/nacre/ime/input/UserLearner.kt`
- Modify: `ime-core/src/test/kotlin/space/manus/nacre/ime/input/UserLearnerTest.kt`

- [ ] **Step 1: Write tests**

```kotlin
@Test
fun domainBoost_separatePackages() {
    val learner = createTestLearner()
    learner.setCurrentDomain("com.termux")
    learner.recordSelection(makeCandidate("git", "git"))
    learner.setCurrentDomain("com.google.android.gm")
    val boost = learner.getDomainBoost("git", "git")
    assertEquals(0, boost) // different domain, no boost
}
```

- [ ] **Step 2: Implement domain separation**

Per spec section 4.4. SharedPreferences `"nacre_domain_boost_{packageName}"` for top 5 domains.

- [ ] **Step 3: Add POS-tagged bigram**

Per spec section 4.2. Prefix bigram keys with POS category.

- [ ] **Step 4: Add 4-gram learning**

Per spec section 4.3. Upper limit 1000 entries.

- [ ] **Step 5: Run tests, commit**

```bash
git commit -m "feat: add domain-specific learning, POS bigrams, and 4-gram support"
```

---

## Phase 5: CandidateRanker Integration

### Task 19: Wire CandidateRanker with KenLM model selection

**Files:**
- Modify: `ime-core/src/main/kotlin/space/manus/nacre/ime/input/ConversionPipeline.kt`
- Modify: IME service startup code

- [ ] **Step 1: Integrate selectModel() → configureWeights() in startup**

When IME initializes:
1. `ModelDownloader.extractBundledKenLm(context)`
2. `val path = KenLmScorer.selectModel(filesDir, externalDirs)`
3. `scorer.load(path)`
4. `ranker.configureWeights(scorer.getModelOrder())`

- [ ] **Step 2: Run full build and manual test**

Run: `cd ~/Nacre && ./gradlew :app:assembleDebug 2>&1 | tail -10`

- [ ] **Step 3: Commit**

```bash
git commit -m "feat: integrate KenLM model selection and weight branching on IME startup"
```

---

## Phase 6: Voice Pipeline Enhancement

### Task 20: Streaming recognition with SenseVoice chunks

**Files:**
- Modify: `ime-core/src/main/kotlin/space/manus/nacre/ime/input/VoiceInputManager.kt`
- Modify: `ime-ai/src/main/kotlin/space/manus/nacre/ai/WhisperService.kt`

- [ ] **Step 1a: Modify AIDL interface**

Add `onPartialResult(text: String, isStable: Boolean)` to `IWhisperCallback.aidl`. This requires regenerating AIDL stubs.

- [ ] **Step 1b: Update WhisperService to emit per-chunk results**

In WhisperService, after each VAD-detected speech chunk is processed by SenseVoice:
```kotlin
callback?.onPartialResult(chunkText, isStable = false)
```

- [ ] **Step 1c: Update VoiceInputManager AIDL binding**

Implement the new `onPartialResult` callback in VoiceInputManager's `IWhisperCallback.Stub`.

- [ ] **Step 2: Update VoiceInputManager streaming logic**

Use existing `partialStableCount` (line 57) pattern for SenseVoice:
- On each chunk result: compare with `lastPartialText`
- If identical 3 consecutive times → commit prefix as stable
- If chunk interval > 500ms → lower threshold to 2

- [ ] **Step 3: Display composing text in real-time**

```kotlin
inputConnection?.setComposingText(partialText, 1)
// On stable commit:
inputConnection?.commitText(stablePrefix, 1)
```

- [ ] **Step 4: Commit**

```bash
git commit -m "feat: add real-time streaming display for SenseVoice voice input"
```

---

### Task 21: Enhanced punctuation with KenLM scoring

**Files:**
- Modify: `ime-core/src/main/kotlin/space/manus/nacre/ime/input/VoiceInputManager.kt`

- [ ] **Step 1: Add KenLM-based punctuation selection**

In `smartPunctuation()` (VoiceInputManager lines 721-817):

```kotlin
// After pattern-based detection, add KenLM layer:
if (kenLmScorer?.isReady() == true) {
    val candidates = listOf("。", "？", "！", "、")
    val scores = candidates.map { punct ->
        kenLmScorer.score(listOf(text + punct), precedingText)
    }
    val bestIdx = scores.withIndex().maxByOrNull { it.value }?.index ?: 0
    // Weighted: KenLM 0.5 + pattern 0.3 + pause 0.2
    val finalScores = scores.mapIndexed { i, lmScore ->
        lmScore * 0.5f + patternScores[i] * 0.3f + pauseScores[i] * 0.2f
    }
}
```

- [ ] **Step 2: Add pause-based punctuation hints**

Track VAD silence duration between chunks. Pass as `pauseDurationMs` to punctuation logic.

- [ ] **Step 3: Commit**

```bash
git commit -m "feat: add 3-layer punctuation scoring (KenLM + pattern + pause)"
```

---

### Task 22: Context-aware filler removal

**Files:**
- Modify: `ime-ai/src/main/kotlin/space/manus/nacre/ai/PostProcessor.kt`
- Modify: `ime-ai/src/test/kotlin/space/manus/nacre/ai/PostProcessorTest.kt`

- [ ] **Step 1: Write tests**

```kotlin
@Test
fun contextFiller_anoHito_notRemoved() {
    val result = processor.process("あの人に聞いてください")
    assertEquals("あの人に聞いてください。", result.text)
}

@Test
fun contextFiller_anoSumimasen_removed() {
    val result = processor.process("あのすみません")
    assertEquals("すみません。", result.text)
}
```

- [ ] **Step 2: Implement context-aware filler detection**

Check the word following potential filler. If it starts a noun phrase (common patterns: 人, 時, 日, 方, etc.), keep the word as a demonstrative, not a filler.

- [ ] **Step 3: Run existing 128 tests + new tests**

Run: `cd ~/Nacre && ./gradlew :ime-ai:test --tests "*.PostProcessorTest" -i 2>&1 | tail -20`
Expected: All pass (no regression in existing tests).

- [ ] **Step 4: Commit**

```bash
git commit -m "feat: context-aware filler detection (あの/その as demonstrative vs filler)"
```

---

### Task 22b: User-specific filler auto-learning

**Files:**
- Modify: `ime-ai/src/main/kotlin/space/manus/nacre/ai/PostProcessor.kt`
- Modify: `ime-ai/src/test/kotlin/space/manus/nacre/ai/PostProcessorTest.kt`

Covers spec section 5.3 paragraph 2: "User-specific filler learning."

- [ ] **Step 1: Write tests**

```kotlin
@Test
fun fillerAutoLearn_afterFiveDeletions_autoRemoved() {
    val proc = PostProcessor()
    // Simulate user deleting "なんだろう" 5 times after voice input
    repeat(5) { proc.recordFillerDeletion("なんだろう") }
    val result = proc.process("なんだろう今日は寒い")
    assertEquals("今日は寒い。", result.text)
}
```

- [ ] **Step 2: Implement filler deletion tracking**

Add `userFillerCounts: MutableMap<String, Int>` to PostProcessor. Method `recordFillerDeletion(word)` increments count. When count >= 5, add to internal filler list.

- [ ] **Step 3: Persist user fillers to SharedPreferences**

Key: `"nacre_user_fillers"`, format: word\tcount per line.

- [ ] **Step 4: Run tests, commit**

```bash
git commit -m "feat: auto-learn user-specific fillers from manual deletion patterns"
```

---

### Task 22c: Rephrase detection

**Files:**
- Modify: `ime-ai/src/main/kotlin/space/manus/nacre/ai/PostProcessor.kt`
- Modify: `ime-ai/src/test/kotlin/space/manus/nacre/ai/PostProcessorTest.kt`

Covers spec section 5.4: "Rephrase detection enhancement."

- [ ] **Step 1: Write tests**

```kotlin
@Test
fun rephraseDetection_semanticOverlap() {
    val result = processor.process("東京に大阪に行きました")
    // Shared suffix "に行きました" → partial replacement: 東京→大阪
    assertEquals("大阪に行きました。", result.text)
}

@Test
fun rephraseDetection_levenshteinSimilar() {
    val result = processor.process("問題がもんだいが発生しました")
    // "問題が" and "もんだいが" are same reading → rephrase
    assertEquals("問題が発生しました。", result.text)
}
```

- [ ] **Step 2: Implement partial correction pattern**

Detect "A...B" where A and B share a common suffix. Replace A with B:
1. Find longest common suffix between consecutive phrases
2. If suffix length >= 2 chars and prefix differs, treat as rephrase
3. Keep the later version (B)

- [ ] **Step 3: Implement Levenshtein-based similarity detection**

Per spec section 5.4: `hasSimilarMeaning()` with Levenshtein ratio > 0.5 or same hiragana reading. Note: this is an approximation (v1 heuristic), not true semantic similarity.

- [ ] **Step 4: Run tests, commit**

```bash
git commit -m "feat: add rephrase detection via suffix matching and Levenshtein similarity"
```

---

### Task 23: Always-on listening mode

**Files:**
- Modify: `ime-core/src/main/kotlin/space/manus/nacre/ime/input/VoiceInputManager.kt`

- [ ] **Step 1: Add continuousMode toggle**

```kotlin
var continuousMode = false

fun toggleContinuousListening() {
    continuousMode = !continuousMode
    if (continuousMode) {
        startListening() // Remove time limit
    } else {
        stopListening()
    }
}
```

- [ ] **Step 2: Implement VAD-driven power saving**

When `continuousMode = true`:
- Keep VAD running always
- Only start SenseVoice inference when VAD detects speech
- Stop SenseVoice inference after 2s silence
- Insert paragraph break after 2s silence

- [ ] **Step 3: Add paragraph segmentation**

When silence > 2 seconds in continuous mode, insert `\n\n` as paragraph separator.

- [ ] **Step 4: Commit**

```bash
git commit -m "feat: add always-on continuous listening with VAD-driven power saving"
```

---

### Task 24: User correction learning for voice

**Files:**
- Modify: `ime-core/src/main/kotlin/space/manus/nacre/ime/input/VoiceInputManager.kt`
- Modify: `ime-ai/src/main/kotlin/space/manus/nacre/ai/LlmPostProcessor.kt`

- [ ] **Step 1: Track voice commit → manual edit pattern**

In VoiceInputManager, record `lastVoiceCommitText` and `lastVoiceCommitTime`. Expose to InputEngine's `onTextChanged()` listener.

- [ ] **Step 2: Record correction pairs**

When user edits within 500ms of voice commit:
```kotlin
val diff = computeSimpleDiff(lastVoiceCommitText, newText)
if (diff.removed.isNotEmpty() && diff.added.isNotEmpty()) {
    correctionStore.record(diff.removed, diff.added)
}
```

- [ ] **Step 3: Auto-promote corrections to quickClean**

In `LlmPostProcessor.quickClean()`, check correction store before processing:
```kotlin
for ((wrong, right) in correctionStore.getPromotedPairs()) {
    text = text.replace(wrong, right)
}
```

- [ ] **Step 4: Commit**

```bash
git commit -m "feat: auto-learn user corrections for voice input misrecognition"
```

---

## Phase 7: PostProcessor Improvements

### Task 25: Expand number processing

**Files:**
- Modify: `ime-ai/src/main/kotlin/space/manus/nacre/ai/PostProcessor.kt`
- Modify: `ime-ai/src/test/kotlin/space/manus/nacre/ai/PostProcessorTest.kt`

- [ ] **Step 1: Write tests**

```kotlin
@Test
fun timeNormalization() {
    val result = processor.process("十四時三十分に会議")
    assertEquals("14:30に会議。", result.text)
}

@Test
fun moneyWithComma() {
    val result = processor.process("二千五百円です")
    assertEquals("2,500円です。", result.text)
}

@Test
fun phoneNumber() {
    val result = processor.process("ゼロサンのよんよんよんの")
    // Phone number grouping is complex — start with basic digit conversion
}
```

- [ ] **Step 2: Implement time normalization**

Pattern: `(漢数字)時(漢数字)分` → `{n}:{mm}`

- [ ] **Step 3: Implement money comma formatting**

After existing `kanjiToNumber()`, if followed by 円/ドル and result ≥ 1000, add comma grouping.

- [ ] **Step 4: Run tests, commit**

```bash
git commit -m "feat: add time, money comma, and phone number normalization"
```

---

### Task 26: Expand tech term dictionary

**Files:**
- Modify: `ime-ai/src/main/kotlin/space/manus/nacre/ai/LlmPostProcessor.kt`

- [ ] **Step 1: Expand TECH_TERMS map from ~40 to 200+ entries**

Add mappings organized by category:

```kotlin
val TECH_TERMS = mapOf(
    // Languages
    "パイソン" to "Python", "ジャバスクリプト" to "JavaScript",
    "タイプスクリプト" to "TypeScript", "ラスト" to "Rust",
    "ゴー" to "Go", "スウィフト" to "Swift",
    "シープラスプラス" to "C++", "ジャバ" to "Java",
    // Frameworks
    "リアクト" to "React", "ビュー" to "Vue",
    "アンギュラー" to "Angular", "ネクスト" to "Next.js",
    "ジャンゴ" to "Django", "フラスク" to "Flask",
    // Tools
    "ドッカー" to "Docker", "クーバネティス" to "Kubernetes",
    "テラフォーム" to "Terraform", "ジェンキンス" to "Jenkins",
    // Cloud
    "エーダブリューエス" to "AWS", "ジーシーピー" to "GCP",
    "アジュール" to "Azure", "バーセル" to "Vercel",
    // Abbreviations
    "シーアイシーディー" to "CI/CD", "エーピーアイ" to "API",
    "エスディーケー" to "SDK", "シーエルアイ" to "CLI",
    "アイディーイー" to "IDE", "オーアールエム" to "ORM",
    // ... 150+ more entries
)
```

- [ ] **Step 2: Commit**

```bash
git commit -m "feat: expand tech term dictionary to 200+ entries"
```

---

### Task 27: Style consistency checking

**Files:**
- Modify: `ime-ai/src/main/kotlin/space/manus/nacre/ai/PostProcessor.kt`
- Modify: `ime-ai/src/test/kotlin/space/manus/nacre/ai/PostProcessorTest.kt`

- [ ] **Step 1: Write tests**

```kotlin
@Test
fun styleConsistency_formalContext_flagsCasual() {
    val proc = PostProcessor()
    // Set context as formal (です/ます)
    proc.process("これは良いです")
    proc.process("問題ありません")
    // Next utterance with casual ending
    val result = proc.process("それはだめだ")
    // Should suggest formal alternative (but NOT force-replace)
    // For v1: just track formality ratio, no action
}
```

- [ ] **Step 2: Implement formality tracker**

Add `formalityRatio` field that tracks `です/ます` vs `だ/である` ratio over last 3 sentences. Expose as a signal to CandidateRanker for candidate reordering (not replacement).

- [ ] **Step 3: Commit**

```bash
git commit -m "feat: add style consistency tracking for formal/casual detection"
```

---

## Phase 8: Inline Dictionary UI

### Task 28: Long-press candidate registration

**Files:**
- Modify: `ime-core/src/main/kotlin/space/manus/nacre/ime/keyboard/CandidateBar.kt`
- Create: `ime-core/src/main/kotlin/space/manus/nacre/ime/keyboard/DictRegistrationSheet.kt`
- Modify: `ime-core/src/main/kotlin/space/manus/nacre/ime/input/UserLearner.kt`

- [ ] **Step 1: Create DictRegistrationSheet composable**

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DictRegistrationSheet(
    reading: String,
    surface: String,
    onRegister: (reading: String, surface: String, posCategory: String) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(16.dp)) {
            Text("辞書に登録", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(value = reading, onValueChange = {}, label = { Text("読み") }, readOnly = true)
            OutlinedTextField(value = surface, onValueChange = {}, label = { Text("表記") }, readOnly = true)
            // POS dropdown
            var selectedPos by remember { mutableStateOf("名詞") }
            val posOptions = listOf("名詞", "固有名詞", "動詞")
            // ExposedDropdownMenu ...
            Button(onClick = { onRegister(reading, surface, selectedPos) }) {
                Text("登録")
            }
        }
    }
}
```

- [ ] **Step 2: Add long-press handler to CandidateBar**

Add `onLongClick` to candidate items. Show `DictRegistrationSheet` on long press.

- [ ] **Step 3: Add registerUserWord with POS to UserLearner**

Map POS category to Mozc POS IDs:
- 名詞 → leftId=1847, rightId=1847
- 固有名詞 → leftId=1921, rightId=1921
- 動詞 → leftId=798, rightId=798

Verify IDs against actual Mozc POS table in `connection.bin` at implementation time.

- [ ] **Step 4: Run build, commit**

```bash
git commit -m "feat: add inline dictionary registration via candidate long-press"
```

---

## Phase 9: Integration Testing & Tuning

### Task 29: Conversion accuracy benchmark

**Files:**
- Create: `ime-core/src/test/kotlin/space/manus/nacre/ime/input/ConversionBenchmarkTest.kt`
- Create: `ime-core/src/test/resources/benchmark_sentences.tsv`

- [ ] **Step 1: Create benchmark test set**

1000 sentences covering: news articles, tech docs, casual conversation, formal business. Format: `input_kana\texpected_surface`.

- [ ] **Step 2: Write benchmark test**

```kotlin
class ConversionBenchmarkTest {
    @Test
    fun conversionAccuracy() {
        val pipeline = createTestPipeline()
        val sentences = loadBenchmark("benchmark_sentences.tsv")
        var correct = 0
        for ((kana, expected) in sentences) {
            val result = pipeline.convert(kana)
            if (result.firstOrNull()?.surface == expected) correct++
        }
        val accuracy = correct.toFloat() / sentences.size
        println("Accuracy: ${accuracy * 100}% ($correct/${sentences.size})")
        assertTrue(accuracy >= 0.85f, "Accuracy $accuracy below target 85%")
    }
}
```

- [ ] **Step 3: Run and record baseline**

- [ ] **Step 4: Tune weights if needed**

Adjust KenLM weights, beam widths, and decay factors based on benchmark results.

- [ ] **Step 5: Commit**

```bash
git commit -m "test: add conversion accuracy benchmark (target 85%+)"
```

---

### Task 30: Final regression test and cleanup

**Files:**
- All modified files

- [ ] **Step 1: Run full test suite**

```bash
cd ~/Nacre && ./gradlew test 2>&1 | tail -30
```

- [ ] **Step 2: Run full build**

```bash
cd ~/Nacre && ./gradlew :app:assembleDebug 2>&1 | tail -10
```

- [ ] **Step 3: Verify snapshot test still passes**

- [ ] **Step 4: Clean up any remaining references to NacreDictionary**

Search for `NacreDictionary` in the codebase and replace with `ConversionPipeline` where needed.

- [ ] **Step 5: Final commit**

```bash
git commit -m "chore: final cleanup and regression verification"
```
