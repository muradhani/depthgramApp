package org.carftaura.depthgramapp

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform