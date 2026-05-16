/*
 * Copyright (c) 2026 Renaud Allard <renaud@allard.it>
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in
 *    the documentation and/or other materials provided with the
 *    distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS
 * FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 * LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN
 * ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGES.
 */

package it.allard.simcountry.telephony

/**
 * ITU-T E.212 mobile country code assignments. Maps every assigned MCC to its
 * ISO 3166-1 alpha-2 country code and the human-readable country name. Some
 * countries hold several MCCs (e.g. the United States: 310-316).
 */
object Mcc {

    data class Country(val iso: String, val name: String, val mccs: List<String>)

    val countries: List<Country> = listOf(
        // Europe
        Country("GR", "Greece", listOf("202")),
        Country("NL", "Netherlands", listOf("204")),
        Country("BE", "Belgium", listOf("206")),
        Country("FR", "France", listOf("208")),
        Country("MC", "Monaco", listOf("212")),
        Country("AD", "Andorra", listOf("213")),
        Country("ES", "Spain", listOf("214")),
        Country("HU", "Hungary", listOf("216")),
        Country("BA", "Bosnia and Herzegovina", listOf("218")),
        Country("HR", "Croatia", listOf("219")),
        Country("RS", "Serbia", listOf("220")),
        Country("XK", "Kosovo", listOf("221")),
        Country("IT", "Italy", listOf("222")),
        Country("VA", "Vatican City", listOf("225")),
        Country("RO", "Romania", listOf("226")),
        Country("CH", "Switzerland", listOf("228")),
        Country("CZ", "Czechia", listOf("230")),
        Country("SK", "Slovakia", listOf("231")),
        Country("AT", "Austria", listOf("232")),
        Country("GB", "United Kingdom", listOf("234", "235")),
        Country("DK", "Denmark", listOf("238")),
        Country("SE", "Sweden", listOf("240")),
        Country("NO", "Norway", listOf("242")),
        Country("FI", "Finland", listOf("244")),
        Country("LT", "Lithuania", listOf("246")),
        Country("LV", "Latvia", listOf("247")),
        Country("EE", "Estonia", listOf("248")),
        Country("RU", "Russia", listOf("250")),
        Country("UA", "Ukraine", listOf("255")),
        Country("BY", "Belarus", listOf("257")),
        Country("MD", "Moldova", listOf("259")),
        Country("PL", "Poland", listOf("260")),
        Country("DE", "Germany", listOf("262")),
        Country("GI", "Gibraltar", listOf("266")),
        Country("PT", "Portugal", listOf("268")),
        Country("LU", "Luxembourg", listOf("270")),
        Country("IE", "Ireland", listOf("272")),
        Country("IS", "Iceland", listOf("274")),
        Country("AL", "Albania", listOf("276")),
        Country("MT", "Malta", listOf("278")),
        Country("CY", "Cyprus", listOf("280")),
        Country("GE", "Georgia", listOf("282")),
        Country("AM", "Armenia", listOf("283")),
        Country("BG", "Bulgaria", listOf("284")),
        Country("TR", "Turkey", listOf("286")),
        Country("FO", "Faroe Islands", listOf("288")),
        Country("GL", "Greenland", listOf("290")),
        Country("SM", "San Marino", listOf("292")),
        Country("SI", "Slovenia", listOf("293")),
        Country("MK", "North Macedonia", listOf("294")),
        Country("LI", "Liechtenstein", listOf("295")),
        Country("ME", "Montenegro", listOf("297")),

        // North America and Caribbean
        Country("CA", "Canada", listOf("302")),
        Country("PM", "Saint Pierre and Miquelon", listOf("308")),
        Country("US", "United States", listOf("310", "311", "312", "313", "314", "315", "316")),
        Country("PR", "Puerto Rico", listOf("330")),
        Country("VI", "United States Virgin Islands", listOf("332")),
        Country("MX", "Mexico", listOf("334")),
        Country("JM", "Jamaica", listOf("338")),
        Country("GP", "Guadeloupe", listOf("340")),
        Country("BB", "Barbados", listOf("342")),
        Country("AG", "Antigua and Barbuda", listOf("344")),
        Country("KY", "Cayman Islands", listOf("346")),
        Country("VG", "British Virgin Islands", listOf("348")),
        Country("BM", "Bermuda", listOf("350")),
        Country("GD", "Grenada", listOf("352")),
        Country("MS", "Montserrat", listOf("354")),
        Country("KN", "Saint Kitts and Nevis", listOf("356")),
        Country("LC", "Saint Lucia", listOf("358")),
        Country("VC", "Saint Vincent and the Grenadines", listOf("360")),
        Country("CW", "Curacao", listOf("362")),
        Country("AW", "Aruba", listOf("363")),
        Country("BS", "Bahamas", listOf("364")),
        Country("AI", "Anguilla", listOf("365")),
        Country("DM", "Dominica", listOf("366")),
        Country("CU", "Cuba", listOf("368")),
        Country("DO", "Dominican Republic", listOf("370")),
        Country("HT", "Haiti", listOf("372")),
        Country("TT", "Trinidad and Tobago", listOf("374")),
        Country("TC", "Turks and Caicos Islands", listOf("376")),

        // South America
        Country("AZ", "Azerbaijan", listOf("400")),
        Country("KZ", "Kazakhstan", listOf("401")),
        Country("BT", "Bhutan", listOf("402")),
        Country("IN", "India", listOf("404", "405", "406")),
        Country("PK", "Pakistan", listOf("410")),
        Country("AF", "Afghanistan", listOf("412")),
        Country("LK", "Sri Lanka", listOf("413")),
        Country("MM", "Myanmar", listOf("414")),
        Country("LB", "Lebanon", listOf("415")),
        Country("JO", "Jordan", listOf("416")),
        Country("SY", "Syria", listOf("417")),
        Country("IQ", "Iraq", listOf("418")),
        Country("KW", "Kuwait", listOf("419")),
        Country("SA", "Saudi Arabia", listOf("420")),
        Country("YE", "Yemen", listOf("421")),
        Country("OM", "Oman", listOf("422")),
        Country("PS", "Palestine", listOf("423")),
        Country("AE", "United Arab Emirates", listOf("424", "430", "431")),
        Country("IL", "Israel", listOf("425")),
        Country("BH", "Bahrain", listOf("426")),
        Country("QA", "Qatar", listOf("427")),
        Country("MN", "Mongolia", listOf("428")),
        Country("NP", "Nepal", listOf("429")),
        Country("IR", "Iran", listOf("432")),
        Country("UZ", "Uzbekistan", listOf("434")),
        Country("TJ", "Tajikistan", listOf("436")),
        Country("KG", "Kyrgyzstan", listOf("437")),
        Country("TM", "Turkmenistan", listOf("438")),
        Country("JP", "Japan", listOf("440", "441")),
        Country("KR", "South Korea", listOf("450")),
        Country("VN", "Vietnam", listOf("452")),
        Country("HK", "Hong Kong", listOf("454")),
        Country("MO", "Macao", listOf("455")),
        Country("KH", "Cambodia", listOf("456")),
        Country("LA", "Laos", listOf("457")),
        Country("CN", "China", listOf("460", "461")),
        Country("TW", "Taiwan", listOf("466")),
        Country("KP", "North Korea", listOf("467")),
        Country("BD", "Bangladesh", listOf("470")),
        Country("MV", "Maldives", listOf("472")),
        Country("MY", "Malaysia", listOf("502")),
        Country("AU", "Australia", listOf("505")),
        Country("ID", "Indonesia", listOf("510")),
        Country("TL", "Timor-Leste", listOf("514")),
        Country("PH", "Philippines", listOf("515")),
        Country("TH", "Thailand", listOf("520")),
        Country("SG", "Singapore", listOf("525")),
        Country("BN", "Brunei", listOf("528")),
        Country("NZ", "New Zealand", listOf("530")),
        Country("MP", "Northern Mariana Islands", listOf("534")),
        Country("GU", "Guam", listOf("535")),
        Country("NR", "Nauru", listOf("536")),
        Country("PG", "Papua New Guinea", listOf("537")),
        Country("TO", "Tonga", listOf("539")),
        Country("SB", "Solomon Islands", listOf("540")),
        Country("VU", "Vanuatu", listOf("541")),
        Country("FJ", "Fiji", listOf("542")),
        Country("WF", "Wallis and Futuna", listOf("543")),
        Country("AS", "American Samoa", listOf("544")),
        Country("KI", "Kiribati", listOf("545")),
        Country("NC", "New Caledonia", listOf("546")),
        Country("PF", "French Polynesia", listOf("547")),
        Country("CK", "Cook Islands", listOf("548")),
        Country("WS", "Samoa", listOf("549")),
        Country("FM", "Micronesia", listOf("550")),
        Country("MH", "Marshall Islands", listOf("551")),
        Country("PW", "Palau", listOf("552")),
        Country("TV", "Tuvalu", listOf("553")),
        Country("NU", "Niue", listOf("555")),

        // Africa
        Country("EG", "Egypt", listOf("602")),
        Country("DZ", "Algeria", listOf("603")),
        Country("MA", "Morocco", listOf("604")),
        Country("TN", "Tunisia", listOf("605")),
        Country("LY", "Libya", listOf("606")),
        Country("GM", "Gambia", listOf("607")),
        Country("SN", "Senegal", listOf("608")),
        Country("MR", "Mauritania", listOf("609")),
        Country("ML", "Mali", listOf("610")),
        Country("GN", "Guinea", listOf("611")),
        Country("CI", "Cote d'Ivoire", listOf("612")),
        Country("BF", "Burkina Faso", listOf("613")),
        Country("NE", "Niger", listOf("614")),
        Country("TG", "Togo", listOf("615")),
        Country("BJ", "Benin", listOf("616")),
        Country("MU", "Mauritius", listOf("617")),
        Country("LR", "Liberia", listOf("618")),
        Country("SL", "Sierra Leone", listOf("619")),
        Country("GH", "Ghana", listOf("620")),
        Country("NG", "Nigeria", listOf("621")),
        Country("TD", "Chad", listOf("622")),
        Country("CF", "Central African Republic", listOf("623")),
        Country("CM", "Cameroon", listOf("624")),
        Country("CV", "Cape Verde", listOf("625")),
        Country("ST", "Sao Tome and Principe", listOf("626")),
        Country("GQ", "Equatorial Guinea", listOf("627")),
        Country("GA", "Gabon", listOf("628")),
        Country("CG", "Republic of the Congo", listOf("629")),
        Country("CD", "Democratic Republic of the Congo", listOf("630")),
        Country("AO", "Angola", listOf("631")),
        Country("GW", "Guinea-Bissau", listOf("632")),
        Country("SC", "Seychelles", listOf("633")),
        Country("SD", "Sudan", listOf("634")),
        Country("RW", "Rwanda", listOf("635")),
        Country("ET", "Ethiopia", listOf("636")),
        Country("SO", "Somalia", listOf("637")),
        Country("DJ", "Djibouti", listOf("638")),
        Country("KE", "Kenya", listOf("639")),
        Country("TZ", "Tanzania", listOf("640")),
        Country("UG", "Uganda", listOf("641")),
        Country("BI", "Burundi", listOf("642")),
        Country("MZ", "Mozambique", listOf("643")),
        Country("ZM", "Zambia", listOf("645")),
        Country("MG", "Madagascar", listOf("646")),
        Country("RE", "Reunion", listOf("647")),
        Country("ZW", "Zimbabwe", listOf("648")),
        Country("NA", "Namibia", listOf("649")),
        Country("MW", "Malawi", listOf("650")),
        Country("LS", "Lesotho", listOf("651")),
        Country("BW", "Botswana", listOf("652")),
        Country("SZ", "Eswatini", listOf("653")),
        Country("KM", "Comoros", listOf("654")),
        Country("ZA", "South Africa", listOf("655")),
        Country("ER", "Eritrea", listOf("657")),
        Country("SS", "South Sudan", listOf("659")),

        // South America (E.212 7xx block)
        Country("BZ", "Belize", listOf("702")),
        Country("GT", "Guatemala", listOf("704")),
        Country("SV", "El Salvador", listOf("706")),
        Country("HN", "Honduras", listOf("708")),
        Country("NI", "Nicaragua", listOf("710")),
        Country("CR", "Costa Rica", listOf("712")),
        Country("PA", "Panama", listOf("714")),
        Country("PE", "Peru", listOf("716")),
        Country("AR", "Argentina", listOf("722")),
        Country("BR", "Brazil", listOf("724")),
        Country("CL", "Chile", listOf("730")),
        Country("CO", "Colombia", listOf("732")),
        Country("VE", "Venezuela", listOf("734")),
        Country("BO", "Bolivia", listOf("736")),
        Country("GY", "Guyana", listOf("738")),
        Country("EC", "Ecuador", listOf("740")),
        Country("GF", "French Guiana", listOf("742")),
        Country("PY", "Paraguay", listOf("744")),
        Country("SR", "Suriname", listOf("746")),
        Country("UY", "Uruguay", listOf("748")),
        Country("FK", "Falkland Islands", listOf("750")),
    )

    /** MCC string to its country record. */
    val byMcc: Map<String, Country> = countries
        .flatMap { c -> c.mccs.map { mcc -> mcc to c } }
        .toMap()

    /** ISO alpha-2 to country record. */
    val byIso: Map<String, Country> = countries.associateBy { it.iso }

    fun nameOf(iso: String): String = byIso[iso]?.name ?: iso

    fun mccsOf(iso: String): List<String> = byIso[iso]?.mccs.orEmpty()
}
