# Home and API Product Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the approved C-style Kuikly research dashboard, secure multi-provider API configuration, portfolio risk center, structured mobile answers, and one-tap alert integration, then install and verify the result on the connected Android test phone.

**Architecture:** Keep every screen in Kuikly `commonMain`; place deterministic profile, dashboard, portfolio, and alert behavior in small pure Kotlin units with common tests. Resolve the active OpenAI-compatible profile for every request, while a platform `SecureSecretStore` keeps API keys outside profile JSON; Android encrypts secrets with an Android Keystore AES/GCM key. Existing stock, watchlist, chat, and alert stores remain the source of truth.

**Tech Stack:** Kotlin Multiplatform, Kuikly 2.7, Ktor 2.3, kotlinx.serialization/Kuikly JSON, Android Keystore, Kotlin Test, Gradle, ADB.

---

## File map

- `shared/src/commonMain/kotlin/com/kuikly/stock/ai/config/AiProviderProfile.kt`: profile model, validation, URL normalization, masking, status mapping.
- `shared/src/commonMain/kotlin/com/kuikly/stock/ai/config/AiProfileStore.kt`: preset seeding, metadata persistence, active-profile rules.
- `shared/src/commonMain/kotlin/com/kuikly/stock/ai/config/SecureSecretStore.kt`: common secret-storage contract.
- `shared/src/androidMain/kotlin/com/kuikly/stock/ai/config/SecureSecretStore.android.kt`: Android Keystore AES/GCM implementation.
- `shared/src/iosMain/kotlin/com/kuikly/stock/ai/config/SecureSecretStore.ios.kt`: explicit unavailable implementation until Keychain support is delivered.
- `shared/src/jsMain/kotlin/com/kuikly/stock/ai/config/SecureSecretStore.js.kt`: session-only unsupported implementation for JS.
- `shared/src/commonMain/kotlin/com/kuikly/stock/home/HomeDashboardService.kt`: local dashboard snapshot.
- `shared/src/commonMain/kotlin/com/kuikly/stock/risk/PortfolioRiskCalculator.kt`: deterministic portfolio metrics.
- `shared/src/commonMain/kotlin/com/kuikly/stock/data/ConclusionAlertFactory.kt`: structured conclusion-to-alert mapping.
- `shared/src/commonMain/kotlin/com/kuikly/stock/pages/AppShell.kt`: navigation constants and reusable bottom navigation.
- `shared/src/commonMain/kotlin/com/kuikly/stock/pages/HomeDashboardPage.kt`: approved C homepage.
- `shared/src/commonMain/kotlin/com/kuikly/stock/pages/RiskCenterPage.kt`: portfolio and alert risk UI.
- `shared/src/commonMain/kotlin/com/kuikly/stock/pages/ProfilePage.kt`: settings hub.
- `shared/src/commonMain/kotlin/com/kuikly/stock/pages/ApiConfigPage.kt`: provider list/editor/test UI.
- `shared/src/commonMain/kotlin/com/kuikly/stock/network/DeepSeekApi.kt`: dynamic OpenAI-compatible request config and privacy-safe errors.
- `shared/src/commonMain/kotlin/com/kuikly/stock/pages/ChatMainPage.kt`: standalone chat header, vertical compare cards, folded evidence, alert actions.
- `androidApp/src/main/java/com/kuikly/stock/KuiklyRenderActivity.kt`: launch `home_dashboard`.
- `shared/build.gradle.kts`: remove build-time API-key generation.

### Task 1: Define and test API profile rules

**Files:**
- Create: `shared/src/commonMain/kotlin/com/kuikly/stock/ai/config/AiProviderProfile.kt`
- Create: `shared/src/commonTest/kotlin/com/kuikly/stock/ai/config/AiProviderProfileTest.kt`

- [x] **Step 1: Write the failing profile test**

```kotlin
class AiProviderProfileTest {
    @Test fun normalizesEndpointWithoutInventingV1() {
        val p = AiProviderProfile("p", "Custom", "https://host.example/v1/", "model-a", true)
        assertEquals("https://host.example/v1/chat/completions", p.chatCompletionsUrl())
    }

    @Test fun acceptsHttpsAndLocalHttpOnly() {
        assertNull(validateBaseUrl("https://host.example/v1"))
        assertNull(validateBaseUrl("http://127.0.0.1:8000/v1"))
        assertEquals("生产服务必须使用 HTTPS", validateBaseUrl("http://host.example/v1"))
    }

    @Test fun masksSecretWithoutRevealingPrefix() {
        assertEquals("•••• ·cdef", maskApiKey("abcdefghijklmnopcdef"))
        assertEquals("未配置", maskApiKey(""))
    }

    @Test fun mapsProviderErrorsToActionableMessages() {
        assertEquals("API Key 无效或没有权限", providerErrorMessage(401))
        assertEquals("Base URL 或模型 ID 不正确", providerErrorMessage(404))
        assertEquals("额度不足或请求过于频繁", providerErrorMessage(429))
    }
}
```

- [x] **Step 2: Run RED**

Run:

```powershell
.\gradlew.bat :shared:testDebugUnitTest --tests "com.kuikly.stock.ai.config.AiProviderProfileTest" --console=plain
```

Expected: compilation fails because `AiProviderProfile` and helper functions do not exist.

- [x] **Step 3: Implement the minimal profile rules**

```kotlin
internal data class AiProviderProfile(
    val id: String,
    val name: String,
    val baseUrl: String,
    val model: String,
    val toolsEnabled: Boolean,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    fun chatCompletionsUrl(): String = baseUrl.trim().trimEnd('/') + "/chat/completions"
}

internal fun validateBaseUrl(raw: String): String? {
    val url = raw.trim()
    if (url.isEmpty()) return "请输入 Base URL"
    if (url.startsWith("https://")) return null
    if (url.startsWith("http://127.0.0.1") || url.startsWith("http://localhost")) return null
    return "生产服务必须使用 HTTPS"
}

internal fun maskApiKey(key: String): String =
    if (key.isBlank()) "未配置" else "•••• ·" + key.takeLast(4)

internal fun providerErrorMessage(status: Int): String = when (status) {
    401, 403 -> "API Key 无效或没有权限"
    404 -> "Base URL 或模型 ID 不正确"
    429 -> "额度不足或请求过于频繁"
    else -> "AI 服务请求失败（HTTP $status）"
}
```

- [x] **Step 4: Run GREEN**

Run the Task 1 test command. Expected: all four tests pass.

- [x] **Step 5: Commit**

```powershell
git add shared/src/commonMain/kotlin/com/kuikly/stock/ai/config/AiProviderProfile.kt shared/src/commonTest/kotlin/com/kuikly/stock/ai/config/AiProviderProfileTest.kt
git commit -m "feat(ai): define provider profile rules"
```

### Task 2: Persist multiple profiles and active selection

**Files:**
- Create: `shared/src/commonMain/kotlin/com/kuikly/stock/ai/config/AiProfileCodec.kt`
- Create: `shared/src/commonMain/kotlin/com/kuikly/stock/ai/config/AiProfileStore.kt`
- Create: `shared/src/commonTest/kotlin/com/kuikly/stock/ai/config/AiProfileCodecTest.kt`

- [x] **Step 1: Write failing codec and selection tests**

```kotlin
class AiProfileCodecTest {
    @Test fun presetsContainNoSecret() {
        val presets = defaultAiProfiles(now = 100L)
        assertEquals(listOf("AgentRouter", "DeepSeek 官方"), presets.map { it.name })
        assertEquals("https://co.agentrouter.org/v1", presets.first().baseUrl)
        assertEquals("deepseek-v4-flash", presets.first().model)
    }

    @Test fun roundTripPreservesProfileMetadata() {
        val input = listOf(AiProviderProfile("p", "P", "https://p.example/v1", "m", false, 1L, 2L))
        assertEquals(input, decodeAiProfiles(encodeAiProfiles(input)))
    }

    @Test fun removingActiveProfileSelectsFirstRemainingProfile() {
        val result = selectAfterRemoval(listOf("a", "b", "c"), activeId = "b", removedId = "b")
        assertEquals("a", result)
    }
}
```

- [x] **Step 2: Run RED**

Run the test class and confirm unresolved codec/store symbols.

- [x] **Step 3: Implement codec, presets, and store**

Use Kuikly `JSONObject`/`JSONArray` to encode only the fields in `AiProviderProfile`; never add an API-key field. `AiProfileStore.ensureSeeded()` writes AgentRouter and DeepSeek presets when storage is empty. `saveProfile`, `deleteProfile`, and `setActive` validate IDs and always keep an active remaining profile.

```kotlin
internal object AiProfileStore {
    private const val KEY_PROFILES = "ai_profiles_v1"
    private const val KEY_ACTIVE = "ai_profile_active_v1"

    fun profiles(): List<AiProviderProfile> =
        decodeAiProfiles(appPrefsGet(KEY_PROFILES)).ifEmpty { defaultAiProfiles() }

    fun active(): AiProviderProfile = profiles().firstOrNull { it.id == appPrefsGet(KEY_ACTIVE) }
        ?: profiles().first()

    fun setActive(id: String) {
        require(profiles().any { it.id == id })
        appPrefsSet(KEY_ACTIVE, id)
    }
}
```

- [x] **Step 4: Run GREEN and full shared tests**

```powershell
.\gradlew.bat :shared:testDebugUnitTest --console=plain
```

Expected: all tests pass.

- [x] **Step 5: Commit**

```powershell
git add shared/src/commonMain/kotlin/com/kuikly/stock/ai/config shared/src/commonTest/kotlin/com/kuikly/stock/ai/config
git commit -m "feat(ai): persist multiple provider profiles"
```

### Task 3: Add Android encrypted API-key storage

**Files:**
- Create: `shared/src/commonMain/kotlin/com/kuikly/stock/ai/config/SecureSecretStore.kt`
- Create: `shared/src/androidMain/kotlin/com/kuikly/stock/ai/config/SecureSecretStore.android.kt`
- Create: `shared/src/iosMain/kotlin/com/kuikly/stock/ai/config/SecureSecretStore.ios.kt`
- Create: `shared/src/jsMain/kotlin/com/kuikly/stock/ai/config/SecureSecretStore.js.kt`
- Modify: `shared/build.gradle.kts`

- [x] **Step 1: Add the common contract and confirm compilation is RED**

```kotlin
internal expect object SecureSecretStore {
    fun get(profileId: String): String?
    fun put(profileId: String, secret: String)
    fun remove(profileId: String)
    fun isSecureStorageAvailable(): Boolean
}
```

Run `:shared:compileDebugKotlinAndroid`; expected: missing Android actual implementation.

- [x] **Step 2: Implement Android Keystore AES/GCM storage**

Use alias `kuikly_stock_ai_profile_key_v1`, `KeyGenParameterSpec` with encrypt/decrypt purposes, GCM mode, no padding, and a fresh 12-byte IV. Store `Base64(iv):Base64(ciphertext)` in private SharedPreferences `secure_ai_profiles_v1`. Derive preference keys from a SHA-256 hash of `profileId`, not raw profile names.

```kotlin
private fun encrypt(value: String): String {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
    val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
    return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
        Base64.encodeToString(encrypted, Base64.NO_WRAP)
}
```

Decrypt failures return `null` and delete only the unreadable entry. `put(profileId, "")` delegates to `remove`.

- [x] **Step 3: Add explicit platform fallbacks**

iOS and JS actual implementations return `false` from `isSecureStorageAvailable`; `get` returns null; `put` throws an actionable `IllegalStateException`; `remove` is idempotent. This prevents insecure plaintext fallback.

- [x] **Step 4: Remove build-time key injection**

Delete `dshReadEnv`, `dshEscapeKotlinString`, `aiSecretsDir`, `generateAiSecrets`, and the generated source directory from `shared/build.gradle.kts`. Replace `DeepSeekConfig` constants with non-secret preset defaults only.

- [x] **Step 5: Compile and inspect APK strings**

```powershell
.\gradlew.bat :shared:compileDebugKotlinAndroid --console=plain
rg -a -l "sk-[A-Za-z0-9_-]{12,}" androidApp/build shared/build
```

Expected: compilation succeeds and the secret scan prints no files.

- [x] **Step 6: Commit**

```powershell
git add shared/build.gradle.kts shared/src/commonMain/kotlin/com/kuikly/stock/ai/config shared/src/androidMain/kotlin/com/kuikly/stock/ai/config shared/src/iosMain/kotlin/com/kuikly/stock/ai/config shared/src/jsMain/kotlin/com/kuikly/stock/ai/config
git commit -m "feat(ai): encrypt user API keys on Android"
```

### Task 4: Route requests through the active runtime profile

**Files:**
- Create: `shared/src/commonMain/kotlin/com/kuikly/stock/ai/config/AiRuntimeConfig.kt`
- Modify: `shared/src/commonMain/kotlin/com/kuikly/stock/network/DeepSeekApi.kt`
- Create: `shared/src/commonTest/kotlin/com/kuikly/stock/ai/config/AiRuntimeConfigTest.kt`

- [x] **Step 1: Write failing runtime-resolution tests**

```kotlin
class AiRuntimeConfigTest {
    @Test fun configuredProfileResolvesRequestConfig() {
        val p = AiProviderProfile("a", "AgentRouter", "https://co.agentrouter.org/v1", "m", true)
        assertEquals(
            AiRequestConfig("a", "AgentRouter", "https://co.agentrouter.org/v1/chat/completions", "m", "secret", true),
            resolveAiRequestConfig(p, "secret")
        )
    }

    @Test fun blankSecretRequiresConfiguration() {
        val p = AiProviderProfile("a", "A", "https://a.example/v1", "m", true)
        assertFailsWith<AiConfigurationException> { resolveAiRequestConfig(p, "") }
    }
}
```

- [x] **Step 2: Run RED**

Expected: unresolved runtime types.

- [x] **Step 3: Implement resolver and dynamic client**

`AiRuntimeConfig.current()` combines `AiProfileStore.active()` with `SecureSecretStore.get(id)`. In `DeepSeekApi`, resolve once at the start of each public request and pass the immutable `AiRequestConfig` through body creation and HTTP posting. Replace all `DeepSeekConfig.MODEL/BASE_URL/API_KEY` reads.

- [x] **Step 4: Make logs privacy-safe and map errors**

Log only provider name, model, status, and elapsed milliseconds. Do not log request JSON, Authorization, user content, response body, or server error text. For non-2xx responses throw `AiProviderException(status, providerErrorMessage(status))`.

- [x] **Step 5: Add minimal connection test**

```kotlin
suspend fun testConnection(profile: AiProviderProfile, apiKey: String): AiConnectionResult
```

Send a system/user request that asks for `OK`, with `max_tokens = 8`, no tools, and return elapsed time plus a sanitized status.

- [x] **Step 6: Run GREEN and full tests, then commit**

```powershell
.\gradlew.bat :shared:testDebugUnitTest --console=plain
git add shared/src/commonMain/kotlin/com/kuikly/stock/ai/config shared/src/commonMain/kotlin/com/kuikly/stock/network/DeepSeekApi.kt shared/src/commonTest/kotlin/com/kuikly/stock/ai/config
git commit -m "feat(ai): use active OpenAI-compatible profile"
```

### Task 5: Build deterministic dashboard and portfolio risk models

**Files:**
- Create: `shared/src/commonMain/kotlin/com/kuikly/stock/home/HomeDashboardService.kt`
- Create: `shared/src/commonMain/kotlin/com/kuikly/stock/risk/PortfolioRiskCalculator.kt`
- Create: `shared/src/commonTest/kotlin/com/kuikly/stock/home/HomeDashboardServiceTest.kt`
- Create: `shared/src/commonTest/kotlin/com/kuikly/stock/risk/PortfolioRiskCalculatorTest.kt`

- [x] **Step 1: Write failing portfolio tests**

```kotlin
@Test fun calculatesPnlAndConcentration() {
    val result = PortfolioRiskCalculator.calculate(listOf(
        HoldingSnapshot("600519", "贵州茅台", "白酒", 10.0, 1000.0, 1200.0),
        HoldingSnapshot("000858", "五粮液", "白酒", 20.0, 50.0, 40.0),
    ))
    assertEquals(10800.0, result.totalCost, 0.001)
    assertEquals(12800.0, result.marketValue, 0.001)
    assertEquals(2000.0, result.pnl, 0.001)
    assertEquals(0.9375, result.maxStockWeight, 0.0001)
    assertEquals(1.0, result.maxIndustryWeight, 0.0001)
}

@Test fun emptyPortfolioHasNoFakeRisk() {
    assertEquals(PortfolioRiskSummary.empty(), PortfolioRiskCalculator.calculate(emptyList()))
}
```

- [x] **Step 2: Write failing dashboard summary tests**

Test positive/negative market breadth, stale data, no-watchlist state, triggered alert priority, and deterministic text such as `市场涨跌家数偏弱`.

- [x] **Step 3: Run RED**

Run both test classes; expected unresolved calculators.

- [x] **Step 4: Implement pure calculations**

Portfolio weights use current market value. Risk badges use fixed boundaries: single stock ≥ 50% is high concentration, industry ≥ 70% is high concentration, loss ≤ -15% is high drawdown risk. Missing price excludes an item from totals and records it as unavailable.

- [x] **Step 5: Implement dashboard service adapter**

Read `StockDb.marketOverview()`, `WatchStore.list()`, latest detail/indicator data, and `AlertEngine.hits()`. Cache the snapshot for 30 seconds and invalidate after data refresh.

- [x] **Step 6: Run GREEN and commit**

```powershell
.\gradlew.bat :shared:testDebugUnitTest --console=plain
git add shared/src/commonMain/kotlin/com/kuikly/stock/home shared/src/commonMain/kotlin/com/kuikly/stock/risk shared/src/commonTest/kotlin/com/kuikly/stock/home shared/src/commonTest/kotlin/com/kuikly/stock/risk
git commit -m "feat: add dashboard and portfolio risk models"
```

### Task 6: Convert structured conclusions into alert rules

**Files:**
- Create: `shared/src/commonMain/kotlin/com/kuikly/stock/data/ConclusionAlertFactory.kt`
- Create: `shared/src/commonTest/kotlin/com/kuikly/stock/data/ConclusionAlertFactoryTest.kt`
- Modify: `shared/src/commonMain/kotlin/com/kuikly/stock/network/DeepSeekApi.kt`

- [x] **Step 1: Write failing alert-mapping tests**

```kotlin
@Test fun supportCreatesPriceBelowAlert() {
    val rule = ConclusionAlertFactory.support("600519", "贵州茅台", 1305.0)
    assertEquals(1, rule.type)
    assertEquals(1305.0, rule.threshold)
}

@Test fun resistanceCreatesPriceAboveAlert() {
    val rule = ConclusionAlertFactory.resistance("600519", "贵州茅台", 1338.0)
    assertEquals(0, rule.type)
    assertEquals(1338.0, rule.threshold)
}
```

- [x] **Step 2: Run RED, implement factory, run GREEN**

The factory rejects blank codes, non-finite prices, and prices ≤ 0. Extend the AI card schema with numeric `support_value`, `resistance_value`, `data_date`, and `indicator_date`; reminder creation uses only numeric fields.

- [x] **Step 3: Commit**

```powershell
git add shared/src/commonMain/kotlin/com/kuikly/stock/data/ConclusionAlertFactory.kt shared/src/commonMain/kotlin/com/kuikly/stock/network/DeepSeekApi.kt shared/src/commonTest/kotlin/com/kuikly/stock/data/ConclusionAlertFactoryTest.kt
git commit -m "feat(alerts): create alerts from AI conclusions"
```

### Task 7: Add the Kuikly application shell and C-style homepage

**Files:**
- Create: `shared/src/commonMain/kotlin/com/kuikly/stock/pages/AppShell.kt`
- Create: `shared/src/commonMain/kotlin/com/kuikly/stock/pages/HomeDashboardPage.kt`
- Modify: `androidApp/src/main/java/com/kuikly/stock/KuiklyRenderActivity.kt`
- Modify: `shared/src/commonMain/kotlin/com/kuikly/stock/pages/StockListPage.kt`
- Modify: `shared/src/commonMain/kotlin/com/kuikly/stock/pages/WatchlistPage.kt`

- [x] **Step 1: Create shared navigation constants**

```kotlin
internal object AppRoutes {
    const val HOME = "home_dashboard"
    const val MARKET = "stock_list"
    const val CHAT = "chat_main"
    const val WATCHLIST = "watchlist"
    const val PROFILE = "profile"
    const val RISK = "risk_center"
    const val API_CONFIG = "api_config"
}
```

Implement a reusable five-item bottom navigation. Each item has a 44dp minimum target, visible selected state, and an accessibility label.

- [x] **Step 2: Implement `HomeDashboardPage`**

Use Kuikly `View`, `Text`, `Scroller`, `vif`, and `vfor` only. Match the approved mockup: white top bar, navy brief card, 2×2 research modules, today-focus list, and bottom navigation. Load `HomeDashboardService.snapshot()` in `viewDidLoad` and refresh on foreground entry.

- [x] **Step 3: Change the Android launch page**

Change the no-intent fallback from `chat_main` to `home_dashboard`. Preserve explicit page intents for tests and deep links.

- [x] **Step 4: Align existing market and watchlist navigation**

Add bottom navigation or consistent back behavior without embedding chat controls into those pages.

- [x] **Step 5: Compile and commit**

```powershell
.\gradlew.bat :shared:compileDebugKotlinAndroid --console=plain
git add shared/src/commonMain/kotlin/com/kuikly/stock/pages androidApp/src/main/java/com/kuikly/stock/KuiklyRenderActivity.kt
git commit -m "feat(ui): add research dashboard home"
```

### Task 8: Add risk center, profile hub, and API configuration UI

**Files:**
- Create: `shared/src/commonMain/kotlin/com/kuikly/stock/pages/RiskCenterPage.kt`
- Create: `shared/src/commonMain/kotlin/com/kuikly/stock/pages/ProfilePage.kt`
- Create: `shared/src/commonMain/kotlin/com/kuikly/stock/pages/ApiConfigPage.kt`

- [ ] **Step 1: Implement risk-center states**

Render empty, partial-price, and complete portfolio states. Show total market value, P&L, return, maximum stock weight, maximum industry weight, each holding, and active/triggered alerts. Never render zero as a valid total when no priced holding exists.

- [ ] **Step 2: Implement profile hub**

Move API configuration, data refresh, data-source status, privacy statement, and application version into `ProfilePage`. Keep each row as a 44dp target.

- [ ] **Step 3: Implement API profile list and editor**

The list shows name, Base URL, model, active status, tool status, and masked key. The editor validates every field, leaves an existing key unchanged when the key input is empty, and clears the input after save. Add enable, edit, delete, and add-custom actions.

- [ ] **Step 4: Implement asynchronous connection testing**

Disable the test button while running; show success with provider/model/elapsed time or the sanitized mapped error. Never copy the key into observable status strings.

- [ ] **Step 5: Compile and commit**

```powershell
.\gradlew.bat :shared:compileDebugKotlinAndroid --console=plain
git add shared/src/commonMain/kotlin/com/kuikly/stock/pages/RiskCenterPage.kt shared/src/commonMain/kotlin/com/kuikly/stock/pages/ProfilePage.kt shared/src/commonMain/kotlin/com/kuikly/stock/pages/ApiConfigPage.kt
git commit -m "feat(ui): add risk and API settings pages"
```

### Task 9: Refine the standalone AI research room

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/kuikly/stock/pages/ChatMainPage.kt`
- Modify: `shared/src/commonMain/kotlin/com/kuikly/stock/data/CompareCards.kt`

- [ ] **Step 1: Replace crowded top-level controls**

Use a back button, title `AI 研究室`, and active profile/model chip. Move market, watchlist, data source, and refresh actions out of the chat header/drawer into bottom navigation or profile pages.

- [ ] **Step 2: Render compare results as vertical cards**

Stop rendering five narrow columns. For each stock, render name/code and change on one row, then a 2×2 metric grid for price, MA5, RSI6, and key level. Keep exact numbers unbroken.

- [ ] **Step 3: Add conclusion hierarchy and evidence disclosure**

Render one-line verdict first, then key levels and observation action. Collapse long signal/evidence/source content behind an explicit toggle. Display data and indicator dates beside the source label.

- [ ] **Step 4: Add one-tap alert confirmation**

Support and resistance buttons open a confirmation sheet showing stock, direction, and exact threshold. On confirmation call `WatchStore.upsertAlert` with `ConclusionAlertFactory` output and show a short success toast.

- [ ] **Step 5: Preserve one-shot scroll and compact composer**

Do not change the `ChatScrollCoordinator` contract. Reduce composer vertical height while maintaining a 44dp send target and keyboard-safe padding.

- [ ] **Step 6: Compile, test, and commit**

```powershell
.\gradlew.bat :shared:testDebugUnitTest :shared:compileDebugKotlinAndroid --console=plain
git add shared/src/commonMain/kotlin/com/kuikly/stock/pages/ChatMainPage.kt shared/src/commonMain/kotlin/com/kuikly/stock/data/CompareCards.kt
git commit -m "feat(ui): refine AI research room"
```

### Task 10: Security, accessibility, and migration audit

**Files:**
- Modify: `.env.example`
- Modify: `.gitignore`
- Modify: `shared/src/commonMain/kotlin/com/kuikly/stock/network/DeepSeekApi.kt`
- Modify: Kuikly page files created in Tasks 7–9

- [ ] **Step 1: Remove misleading build-secret instructions**

Change `.env.example` to document optional developer endpoint defaults without an API key, or remove it if no task consumes it. Confirm `.env` remains ignored.

- [ ] **Step 2: Add accessibility semantics supported by Kuikly**

For every navigation, send, add, edit, delete, test, enable, alert, and disclosure target, set the Kuikly accessibility label/role/state available in the pinned framework version. Where the framework cannot expose semantics, ensure visible text and 44dp targets and record the limitation in the verification report.

- [ ] **Step 3: Verify upgrade migration**

Existing chat history, `WatchStore` holdings, alerts, data-source mode, and stock DB keys remain unchanged. New profile keys use the `ai_profiles_v1` namespace and do not overwrite previous preferences.

- [ ] **Step 4: Scan for secrets and unsafe logs**

```powershell
rg -l --hidden --glob '!.git/**' --glob '!.env' --glob '!**/build/**' -S 'sk-[A-Za-z0-9_-]{12,}' .
rg -n 'Authorization|response received|bodyAsText.*println|API_KEY' shared/src androidApp/src
```

Expected: no committed key; Authorization appears only in request-header construction; no request/response body logging.

- [ ] **Step 5: Commit**

```powershell
git add .env.example .gitignore shared/src/commonMain androidApp/src/main
git commit -m "chore: harden privacy and accessibility"
```

### Task 11: Full verification, phone configuration, and delivery

**Files:**
- Modify only files required to fix failures discovered by verification.

- [ ] **Step 1: Run fresh automated verification**

```powershell
.\gradlew.bat :shared:testDebugUnitTest :androidApp:assembleDebug --console=plain
python .\data-pipeline\test_quality_gate.py
git diff --check
```

Expected: Gradle build succeeds, all Kotlin tests pass, all seven data tests pass, and diff check is empty.

- [ ] **Step 2: Verify APK contains no supplied secret**

Use binary-safe filename-only scans against the fresh APK and intermediate assets. Do not print the secret itself or any matching content.

- [ ] **Step 3: Upgrade-install on the connected phone**

```powershell
& 'C:\Users\liaoh\AppData\Local\Android\Sdk\platform-tools\adb.exe' -s 10AFAL2X57003GP install -r -d .\androidApp\build\outputs\apk\debug\androidApp-debug.apk
```

Expected: `Success`, with previous application data retained.

- [ ] **Step 4: Enter the user-supplied key only through the phone UI**

Select the AgentRouter preset, enter the provided key without echoing it to terminal output, save it to encrypted storage, and run connection test. If ADB text injection would expose the key in process arguments or logs, require the user to type it directly instead.

- [ ] **Step 5: Run the phone acceptance matrix**

Verify home, all five navigation destinations, profile switching, model editing, connection testing, chat response, one-shot scroll, vertical comparison, one-tap alert, risk calculations, history retention, keyboard behavior, large-text behavior, back navigation, and notification behavior. Capture screenshots without sensitive fields visible.

- [ ] **Step 6: Measure performance and inspect logs**

Collect cold-start time, `dumpsys gfxinfo` after repeated long scrolling, `dumpsys meminfo`, and PID-filtered warning/error logs. Confirm no crash, ANR, key, prompt, or response text appears.

- [ ] **Step 7: Commit fixes, push, and verify remote HEAD**

```powershell
git status --short
git push origin main
git ls-remote origin refs/heads/main
git rev-parse HEAD
```

Expected: clean worktree and identical local/remote commit hashes.
