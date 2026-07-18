plugins {
    // foojay-resolver: erlaubt Gradle, ein passendes Toolchain-JDK (hier 21)
    // automatisch zu beschaffen, wenn lokal nur 24/26 installiert sind.
    // (1:1 aus Hoshi 0.5 übernommen.)
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "hoshi"

include("core-domain")
include("adapters-brain")
include("adapters-tts")
include("adapters-stt")
include("adapters-speaker")
include("adapters-knowledge")
include("adapters-memory")
include("adapters-routing")
include("adapters-supervision")
include("adapters-ha")
include("adapters-radio")
include("adapters-escalation")
include("capability-kernel")
include("web-inbound")
