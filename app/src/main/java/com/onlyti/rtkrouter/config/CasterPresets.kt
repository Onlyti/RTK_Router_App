package com.onlyti.rtkrouter.config

/** A hardcoded, frequently-used NTRIP caster (host/port/mount). User enters own credentials. */
data class CasterPreset(
    val country: String,
    val name: String,
    val host: String,
    val port: Int,
    val hint: String,
    val defaultMount: String = "",
)

/**
 * Verified public NTRIP casters, grouped by country, for the quick-fill combobox.
 * Only host/port are preset; user enters their own account. Add entries here as confirmed.
 *
 * KR sources verified 2026-06 (gnssdata.or.kr:2101, gnss.eseoul.go.kr:2101).
 */
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
            hint = "고정국망. mount 예: SUWN-RTCM32 (관측소명-포맷)",
        ),
        CasterPreset(
            country = "KR",
            name = "서울특별시 (eseoul)",
            host = "gnss.eseoul.go.kr",
            port = 2101,
            hint = "VRS. mount 예: VRS-RTCM32, user 예: seoul",
        ),
        CasterPreset(
            country = "WW",
            name = "RTK2GO (community, free)",
            host = "rtk2go.com",
            port = 2101,
            hint = "커뮤니티 고정국. NEAREST + Scan 으로 근처 base 선택. user=이메일, pw=아무거나",
        ),
    )

    val COUNTRIES: List<String> = ALL.map { it.country }.distinct()

    fun forCountry(country: String): List<CasterPreset> = ALL.filter { it.country == country }
}
