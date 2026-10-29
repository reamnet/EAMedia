import org.jetbrains.kotlin.gradle.dsl.JvmTarget
plugins { `java-library`; id("org.jetbrains.kotlin.jvm"); id("net.neoforged.moddev") }
group = "mods.eamedia"
version = providers.gradleProperty("modVersion").get()
java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }
kotlin { jvmToolchain(21); compilerOptions { jvmTarget.set(JvmTarget.JVM_21) } }

// Build ELN once, or point at any matching published/local core jar. Never embed it in EAMedia.
val elnJar = file(providers.gradleProperty("elnJar").getOrElse("../ELN1.21.1ForkPatch/build/libs/ElectricalAge-3.0.60.jar"))
val verifyEln by tasks.registering {
    doLast { require(elnJar.isFile) { "Build ELN 3.0.60 first, or pass -PelnJar=/absolute/path/ElectricalAge-3.0.60.jar" } }
}
tasks.named("compileKotlin") { dependsOn(verifyEln) }
neoForge {
    version = property("neoVersion") as String
    parchment { minecraftVersion="1.21.1"; mappingsVersion="2024.11.17" }
    runs {
        create("client") { client(); System.getenv("DISPLAY")?.let { environment("DISPLAY",it) } }
        create("server") { server(); programArgument("--nogui") }
        configureEach {
            gameDirectory.set(file("run/$name"))
            // Local file dependencies compile, but NeoForge does not discover them as mods.
            // Supply the already-built core in each ignored development run directory.
            val prepareCore=tasks.register<Copy>("prepare${name.replaceFirstChar { it.uppercase() }}Eln") {
                dependsOn(verifyEln)
                from(elnJar);into(gameDirectory.dir("mods"));rename { "eln-development.jar" }
            }
            taskBefore(prepareCore)
            logLevel=org.slf4j.event.Level.INFO
            if(project.hasProperty("stopAfterStart"))systemProperty("eln.stopAfterStart",project.property("stopAfterStart").toString())
        }
    }
    mods { create("eamedia") { sourceSet(sourceSets.main.get()) } }
    unitTest { enable(); testedMod=mods.getByName("eamedia") }
}
repositories {
    mavenCentral()
    maven { url=uri("https://thedarkcolour.github.io/KotlinForForge/"); content { includeGroup("thedarkcolour") } }
    maven { url=uri("https://mcef-download.cinemamod.com/repositories/releases"); content { includeGroup("com.cinemamod") } }
}
dependencies {
    compileOnly(files(elnJar))
    testImplementation(files(elnJar))
    implementation("thedarkcolour:kotlinforforge-neoforge:${property("kffVersion")}")
    // Translation tooling only; these parsers are never carried in the addon jar.
    compileOnly("com.github.javaparser:javaparser-core:3.26.3")
    compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:${property("kotlinVersion")}")
    implementation("com.cinemamod:mcef-neoforge:2.1.6-1.21.1") { isTransitive=false }
    jarJar("com.cinemamod:mcef-neoforge:2.1.6-1.21.1") { isTransitive=false; version { strictly("2.1.6-1.21.1") } }
    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test-junit"))
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.10.2")
}
tasks.processResources {
    exclude("**/*.lang")
    inputs.property("version",project.version.toString())
    filesMatching("META-INF/neoforge.mods.toml") { expand(mapOf("version" to project.version.toString())) }
}
tasks.named<Test>("test") { useJUnitPlatform() }
// Small variant supports client-hosted TVs and jukebox server logic. Optional
// server-owned TV browser workers need the full bundled Chromium payload.
val serverJar by tasks.registering(Jar::class) {
    archiveClassifier.set("server")
    dependsOn(tasks.named("jar"))
    from(tasks.named<Jar>("jar").map { zipTree(it.archiveFile.get().asFile) })
    exclude("assets/eln/media-runtime/**","assets/eamedia/chromium/**","META-INF/MANIFEST.MF")
}
tasks.named("assemble") { dependsOn(serverJar) }
tasks.register<JavaExec>("generateLangFiles") {
    dependsOn(tasks.named("classes"))
    classpath=sourceSets.main.get().output+configurations.compileClasspath.get()
    mainClass.set("mods.eln.i18n.LanguageFileUpdater")
    args("src/main","src/main/resources/assets/eamedia/lang")
}

val generateLangJson by tasks.registering {
    group = "build"
    description = "Converts assets/eamedia/lang/*.lang into the JSON lang files Minecraft 1.13+ loads."
    val src = layout.projectDirectory.dir("src/main/resources/assets/eamedia/lang")
    val out = layout.buildDirectory.dir("generated/lang")
    inputs.dir(src)
    outputs.dir(out)
    doLast {
        val dir = out.get().dir("assets/eamedia/lang").asFile
        dir.mkdirs()
        fun esc(s: String) = buildString {
            s.forEach { c ->
                when (c) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (c < ' ') append(String.format("\\u%04x", c.code)) else append(c)
                }
            }
        }
        src.asFile.listFiles { f -> f.extension == "lang" }!!.sorted().forEach { f ->
            val entries = LinkedHashMap<String, String>()
            f.readLines(Charsets.UTF_8).forEach { line ->
                if (line.isBlank() || line.startsWith("#")) return@forEach
                // First '=' not escaped as "\=" (encodeLangKey escapes '=' inside keys).
                val i = Regex("(?<!\\\\)=").find(line)?.range?.first ?: return@forEach
                entries[line.substring(0, i)] = line.substring(i + 1)
            }
            File(dir, f.nameWithoutExtension.lowercase() + ".json")
                .writeText(entries.entries.joinToString(",\n", "{\n", "\n}\n") { "  \"${esc(it.key)}\": \"${esc(it.value)}\"" }, Charsets.UTF_8)
        }
    }
}
sourceSets.main { resources.srcDir(generateLangJson) }
