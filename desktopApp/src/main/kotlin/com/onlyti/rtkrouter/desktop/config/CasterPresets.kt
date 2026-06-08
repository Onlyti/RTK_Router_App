package com.onlyti.rtkrouter.desktop.config

data class CasterPreset(
    val country: String,
    val name: String,
    val host: String,
    val port: Int,
    val hint: String,
    val defaultMount: String = "",
)

object CasterPresets {
    val ALL: List<CasterPreset> = listOf(
        CasterPreset(
            country = "KR",
            name = "국토지리정보원 VRS (NGII/RTS1)",
            host = "RTS1.ngii.go.kr",
            port = 2101,
            hint = "VRS Multi-GNSS. mount: VRS-RTCM32/34. 가입 ID·공통 pw는 NGII 안내 참고",
            defaultMount = "VRS-RTCM32",
        ),
        CasterPreset(
            country = "KR",
            name = "국토지리정보원 FKP (NGII/RTS2)",
            host = "RTS2.ngii.go.kr",
            port = 2101,
            hint = "FKP 면보정. mount: FKP-RTCM31",
            defaultMount = "FKP-RTCM31",
        ),
        CasterPreset(
            country = "KR",
            name = "국토지리정보원 SSR (NGII/RTS2)",
            host = "RTS2.ngii.go.kr",
            port = 2101,
            hint = "SSR 이동체/저가형 수신기. mount: SSR-SSRG",
            defaultMount = "SSR-SSRG",
        ),
        CasterPreset(
            country = "KR",
            name = "국토지리정보원 통합센터 (gnssdata)",
            host = "www.gnssdata.or.kr",
            port = 2101,
            hint = "고정국망. mount 예: SUWN-RTCM32",
        ),
        CasterPreset(
            country = "KR",
            name = "서울특별시 (eseoul)",
            host = "gnss.eseoul.go.kr",
            port = 2101,
            hint = "VRS. mount 예: VRS-RTCM32",
        ),
        CasterPreset(
            country = "WW",
            name = "RTK2GO (community, free)",
            host = "rtk2go.com",
            port = 2101,
            hint = "커뮤니티 고정국",
        ),
    )
}
