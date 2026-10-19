import java.net.IDN
import java.net.URI
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.Properties
import java.util.TreeSet
import java.util.zip.GZIPOutputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Private build config, never committed: `-Pxdm.<key>=...` or `<key>=...` in private.properties at the repo root
// (both ignored by git). Keys: crashRelayUrl, crashRelayKey, signingStoreFile, signingStorePassword, signingKeyAlias,
// signingKeyPassword, privateRulesFile. A plain checkout needs none of them.
val privateProps = Properties().apply {
    rootProject.file("private.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}

fun privateConfig(key: String): String? =
    (providers.gradleProperty("xdm.$key").orNull ?: privateProps.getProperty(key))?.trim()?.takeIf { it.isNotEmpty() }

fun String.asBuildConfigString(): String = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

// Copies privateRulesFile into a generated assets dir as xdm-private.txt (see FilterLists.PRIVATE_RULES_ASSET).
abstract class CopyPrivateRules : DefaultTask() {
    @get:InputFile
    abstract val rules: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        rules.get().asFile.copyTo(File(out, "xdm-private.txt"))
    }
}

android {
    namespace = "com.olaph.xdm"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.olaph.xdm"
        minSdk = 26
        targetSdk = 35
        versionCode = 13
        versionName = "0.8.1"

        // Empty = crash reporting is not set up in this build.
        buildConfigField("String", "CRASH_RELAY_URL", (privateConfig("crashRelayUrl") ?: "").asBuildConfigString())
        buildConfigField("String", "CRASH_RELAY_KEY", (privateConfig("crashRelayKey") ?: "").asBuildConfigString())
    }

    signingConfigs {
        // With signingStoreFile set it signs debug and release; without, the default Android debug key is used.
        privateConfig("signingStoreFile")?.let { path ->
            val store = rootProject.file(path)
            require(store.isFile) { "signingStoreFile does not exist: $store" }
            getByName("debug") {
                storeFile = store
                storePassword = privateConfig("signingStorePassword")
                keyAlias = privateConfig("signingKeyAlias")
                keyPassword = privateConfig("signingKeyPassword")
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // NewPipeExtractor is compiled for Java 11 and uses newer java.* APIs than API 26 has.
        isCoreLibraryDesugaringEnabled = true
        // NewPipeExtractor is compiled for Java 11 and uses newer java.* APIs than API 26 has.
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

privateConfig("privateRulesFile")?.let { path ->
    val rulesFile = rootProject.file(path)
    require(rulesFile.isFile) { "privateRulesFile does not exist: $rulesFile" }
    val copyPrivateRules = tasks.register<CopyPrivateRules>("copyPrivateRules") {
        rules.set(rulesFile)
    }
    extensions.getByType<com.android.build.api.variant.ApplicationAndroidComponentsExtension>().onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyPrivateRules, CopyPrivateRules::outputDir)
    }
}

// Build-prep (run by hand, not part of the normal build): regenerates the bundled ad blocklist from the
// StevenBlack unified hosts list (MIT). Output: src/main/assets/blocklist.txt.gz, a gzip of
// "# xdm-blocklist updated=<epoch ms>" followed by one lowercase domain per line, sorted.
//   ./gradlew :app:updateBlocklist                      (downloads the list)
//   ./gradlew :app:updateBlocklist -PblocklistSource=/path/to/hosts
// The rules mirror com.olaph.xdm.adblock.HostsParser; BlocklistAssetTest checks the result with the real parser.
tasks.register("updateBlocklist") {
    group = "xdm"
    description = "Fetch the StevenBlack hosts list and convert it to the compact bundled blocklist asset"
    val out = layout.projectDirectory.file("src/main/assets/blocklist.txt.gz").asFile
    val localSource = providers.gradleProperty("blocklistSource")
    doLast {
        val raw = localSource.orNull?.let { File(it).readText() }
            ?: URI("https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts").toURL().readText()
        val ip = Regex("^(0\\.0\\.0\\.0|127\\.0\\.0\\.1)$")
        val label = "[a-z0-9_](?:[a-z0-9_-]{0,61}[a-z0-9_])?"
        val valid = Regex("^$label(?:\\.$label)+$")
        val domains = TreeSet<String>()
        for (line in raw.lineSequence()) {
            val tokens = line.substringBefore('#').trim().split(Regex("\\s+"))
            if (tokens.size < 2 || !ip.matches(tokens[0])) continue
            for (t in tokens.drop(1)) {
                val h = t.lowercase().trimEnd('.')
                if (valid.matches(h) && !h.substringAfterLast('.').all { it.isDigit() } && h != "localhost.localdomain") domains += h
            }
        }
        check(domains.size >= 1000) { "only ${domains.size} domains parsed; refusing to overwrite the asset" }
        val dateLine = Regex("^# Date: (.+?) \\(UTC\\)", RegexOption.MULTILINE).find(raw)?.groupValues?.get(1)
        val millis = dateLine?.let {
            runCatching {
                LocalDateTime.parse(it, DateTimeFormatter.ofPattern("dd MMMM yyyy HH:mm:ss", Locale.ENGLISH))
                    .toInstant(ZoneOffset.UTC).toEpochMilli()
            }.getOrNull()
        } ?: System.currentTimeMillis()
        out.parentFile.mkdirs()
        // Java's GZIPOutputStream writes no file name and a zero mtime, so an unchanged list gives identical bytes.
        GZIPOutputStream(out.outputStream()).bufferedWriter().use { w ->
            w.write("# xdm-blocklist updated=$millis\n")
            domains.forEach { w.write(it); w.write("\n") }
        }
        println("wrote ${out.relativeTo(projectDir)}: ${domains.size} domains, ${out.length()} bytes (list date: $dateLine)")
    }
}

// Build-prep (run by hand, not part of the normal build): refreshes the bundled filter-list snapshots and the
// Public Suffix List. Outputs gzip files in src/main/assets/filters/, each starting with
// "! xdm-list updated=<epoch ms>" (the list's own "last modified" date). The line filters mirror
// com.olaph.xdm.adblock.filters.FilterListFormat, which applies the same preprocessing to in-app updates.
//   ./gradlew :app:updateFilterLists                                  (downloads everything)
//   ./gradlew :app:updateFilterLists -PfilterListsDir=/path/to/dir    (easylist.txt, adguard_base.txt,
//                                                                       adguard_dns.txt, public_suffix_list.dat)
tasks.register("updateFilterLists") {
    group = "xdm"
    description = "Fetch EasyList, AdGuard Base, AdGuard DNS and the Public Suffix List into compact bundled assets"
    val outDir = layout.projectDirectory.dir("src/main/assets/filters").asFile
    val localDir = providers.gradleProperty("filterListsDir")
    doLast {
        fun fetch(file: String, url: String): String =
            localDir.orNull?.let { File(it, file).readText() } ?: URI(url).toURL().readText()

        fun listDate(text: String): Long? {
            val head = text.lineSequence().take(40).toList()
            for (line in head) {
                Regex("^! (?:Last modified|TimeUpdated): (.+)$").find(line.trim())?.groupValues?.get(1)?.trim()?.let { v ->
                    runCatching { OffsetDateTime.parse(v).toInstant().toEpochMilli() }.getOrNull()?.let { return it }
                    runCatching {
                        LocalDateTime.parse(v.removeSuffix(" UTC"), DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm", Locale.ENGLISH))
                            .toInstant(ZoneOffset.UTC).toEpochMilli()
                    }.getOrNull()?.let { return it }
                    runCatching { Instant.parse(v).toEpochMilli() }.getOrNull()?.let { return it }
                }
            }
            return null
        }

        val unsupported = listOf("#$#", "#@$#", "#%#", "#@%#", "#?#", "#@?#", "#$?#", "#@$?#", "##+js(", "#@#+js(", "##^", "#@#^", "$$", "$@$")
        fun keepFilterLine(line: String): Boolean {
            if (line.isEmpty() || line.startsWith("!") || line.startsWith("[Adblock") || line.startsWith("[$")) return false
            if (line == "#" || line.startsWith("# ")) return false
            return unsupported.none { line.contains(it) }
        }

        fun writeGz(name: String, header: String, lines: Sequence<String>): Int {
            outDir.mkdirs()
            val out = File(outDir, name)
            var n = 0
            GZIPOutputStream(out.outputStream()).bufferedWriter().use { w ->
                w.write(header); w.write("\n")
                lines.forEach { w.write(it); w.write("\n"); n++ }
            }
            println("wrote ${out.relativeTo(projectDir)}: $n lines, ${out.length()} bytes")
            return n
        }

        for ((name, file, url) in listOf(
            Triple("easylist.txt.gz", "easylist.txt", "https://easylist.to/easylist/easylist.txt"),
            Triple("adguard-base.txt.gz", "adguard_base.txt", "https://filters.adtidy.org/extension/ublock/filters/2_without_easylist.txt"),
        )) {
            val raw = fetch(file, url)
            val date = listDate(raw) ?: System.currentTimeMillis()
            val n = writeGz(name, "! xdm-list updated=$date", raw.lineSequence().map { it.trim() }.filter(::keepFilterLine))
            check(n >= 5_000) { "$file: only $n rules; refusing to keep it" }
        }

        // AdGuard DNS filter: only whole-host rules are used, stored as "host" / "@@host" lines.
        val dnsRaw = fetch("adguard_dns.txt", "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt")
        val hostRe = Regex("^(@@)?\\|\\|([a-z0-9._-]+)\\^\\|?(\\\$important)?$")
        val badRe = Regex("^\\|\\|([a-z0-9._-]+)\\^\\\$badfilter$")
        val bad = dnsRaw.lineSequence().mapNotNull { badRe.find(it.trim())?.groupValues?.get(1) }.toSet()
        val blocks = TreeSet<String>()
        val allows = TreeSet<String>()
        for (line in dnsRaw.lineSequence()) {
            val m = hostRe.find(line.trim().lowercase()) ?: continue
            val host = m.groupValues[2]
            if (!host.contains('.') || host.startsWith('.') || host.endsWith('.') || host in bad) continue
            if (m.groupValues[1].isNotEmpty()) allows += host else blocks += host
        }
        check(blocks.size >= 10_000) { "AdGuard DNS: only ${blocks.size} hosts" }
        writeGz("adguard-dns.txt.gz", "! xdm-list updated=${listDate(dnsRaw) ?: System.currentTimeMillis()}", blocks.asSequence() + allows.asSequence().map { "@@$it" })

        // Public Suffix List (MPL-2.0): rules only, IDN entries converted to punycode.
        val psl = fetch("public_suffix_list.dat", "https://publicsuffix.org/list/public_suffix_list.dat")
        val rules = psl.lineSequence().map { it.trim().substringBefore(' ') }
            .filter { it.isNotEmpty() && !it.startsWith("//") }
            .map { r ->
                val prefix = r.takeWhile { it == '!' || it == '*' || it == '.' }
                prefix + IDN.toASCII(r.substring(prefix.length), IDN.ALLOW_UNASSIGNED).lowercase()
            }
            .toList()
        check(rules.size >= 5_000) { "PSL: only ${rules.size} rules" }
        writeGz("public_suffix_list.dat.gz", "// xdm-psl updated=${System.currentTimeMillis()}", rules.asSequence())
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.biometric)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.webkit)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.media3.extractor)
    implementation(libs.androidx.media3.muxer)
    implementation(libs.newpipe.extractor)
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.sqlite.jdbc)
    // media3's extractor/muxer use framework classes (SparseArray, MediaCodec.BufferInfo, Log) that the plain
    // android.jar stubs do not implement; the remux tests run on Robolectric's real implementations.
    testImplementation(libs.robolectric)
}
