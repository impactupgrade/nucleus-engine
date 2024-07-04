package com.impactupgrade.nucleus.util;

public enum CountryCallingCode {

  // Zone 1: North American Numbering Plan (NANP)
  US(1),
  UNITED_STATES_VIRGIN_ISLANDS(1340),
  NORTHERN_MARIANA_ISLANDS(1670),
  GUAM(1671),
  AMERICAN_SAMOA(1684),
  PUERTO_RICO(1787),
  PUERTO_RICO_2(1939),
  BAHAMAS(1242),
  BARBADOS(1246),
  ANGUILLA(1264),
  ANTIGUA_AND_BARBUDA(1268),
  BRITISH_VIRGIN_ISLANDS(1284),
  CAYMAN_ISLANDS(1345),
  BERMUDA(1441),
  GRENADA(1473),
  TURKS_AND_CAICOS_ISLANDS(1649),
  JAMAICA(1658),
  JAMAICA_2(1876),
  MONTSERRAT(1664),
  SINT_MAARTEN(1721),
  SAINT_LUCIA(1758),
  DOMINICA(1767),
  SAINT_VINCENT_AND_THE_GRENADINES(1784),
  DOMINICAN_REPUBLIC(1809),
  DOMINICAN_REPUBLIC_2(1829),
  DOMINICAN_REPUBLIC_3(1849),
  TRINIDAD_AND_TOBAGO(1868),
  SAINT_KITTS_AND_NEVIS(1869),

  // Zone 2: Mostly Africa
  EGYPT(20),
  //210 – unassigned
  SOUTH_SUDAN(211),
  MOROCCO(212), // including Western Sahara
  ALGERIA(213),
  //214 – unassigned
  //215 – unassigned
  TUNISIA(216),
  //217 - unassigned
  LIBYA(218),
  //219 - unassigned
  GAMBIA(220),
  SENEGAL(221),
  MAURITANIA(222),
  MALI(223),
  GUINEA(224),
  IVORY_COAST(225),
  BURKINA_FASO(226),
  NIGER(227),
  TOGO(228),
  BENIN(229),
  MAURITIUS(230),
  LIBERIA(231),
  SIERRA_LEONE(232),
  GHANA(233),
  NIGERIA(234),
  CHAD(235),
  CENTRAL_AFRICAN_REPUBLIC(236),
  CAMEROON(237),
  CAPE_VERDE(238),
  SAO_TOME_AND_PRINCIPE(239),
  EQUATORIAL_GUINEA(240),
  GABON(241),
  REPUBLIC_OF_THE_CONGO(242),
  DEMOCRATIC_REPUBLIC_OF_THE_CONGO(243),
  ANGOLA(244),
  GUINEA_BISSAU(245),
  BRITISH_INDIAN_OCEAN_TERRITORY(246),
  ASCENSION_ISLAND(247),
  SEYCHELLES(248),
  SUDAN(249),
  RWANDA(250),
  ETHIOPIA(251),
  SOMALIA(252), // including  Somaliland
  DJIBOUTI(253),
  KENYA(254),
  TANZANIA(255),
  UGANDA(256),
  BURUNDI(257),
  MOZAMBIQUE(258),
  //259 – unassigned
  ZAMBIA(260),
  MADAGASCAR(261),
  REUNION(262),
  ZIMBABWE(263),
  NAMIBIA(264),
  MALAWI(265),
  LESOTHO(266),
  BOTSWANA(267),
  ESWATINI(268),
  COMOROS(269), // formerly assigned to Mayotte, now at 262
  SOUTH_AFRICA(27),
  //28x – unassigned
  SAINT_HELENA(290),
  ERITREA(291),
  //292 – unassigned
  //293 – unassigned
  //294 – unassigned
  //295 – unassigned (formerly assigned to San Marino, now at 378)
  //296 – unassigned
  ARUBA(297),
  FAROE_ISLANDS(298),
  GREENLAND(299),

  // Zones 3–4: Europe
  GREECE(30),
  NETHERLANDS(31),
  BELGIUM(32),
  FRANCE(33),
  SPAIN(34),
  GIBRALTAR(350),
  PORTUGAL(351),
  LUXEMBOURG(352),
  IRELAND(353),
  ICELAND(354),
  ALBANIA(355),
  MALTA(356),
  CYPRUS(357), // including  Akrotiri and Dhekelia
  FINLAND(358),
  BULGARIA(359),
  HUNGARY(36), // formerly assigned to Turkey, now at 90
  LITHUANIA(370),
  LATVIA(371),
  ESTONIA(372),
  MOLDOVA(373),
  ARMENIA(374),
  BELARUS(375),
  ANDORRA(376), // formerly 33 628
  MONACO(377), // formerly 33 93)
  SAN_MARINO(378),
  VATICAN_CITY(379),
  UKRAINE(380),
  SERBIA(381),
  MONTENEGRO(382),
  KOSOVO(383),
  //384 – unassigned
  CROATIA(385),
  SLOVENIA(386),
  BOSNIA_AND_HERZEGOVINA(387),
  //388 – unassigned (formerly assigned to the European Telephony Numbering Space)
  NORTH_MACEDONIA(389),
  ITALY(39),
  ROMANIA(40),
  SWITZERLAND(41),
  CZECH_REPUBLIC(420),
  SLOVAKIA(421),
  //422 – unassigned
  LIECHTENSTEIN_(423), // formerly at 41 (75)
  //424-429 – unassigned
  AUSTRIA(43),
  UNITED_KINGDOM(44),
  DENMARK(45),
  SWEDEN(46),
  NORWAY(47),
  POLAND(48),
  GERMANY(49),

  // Zone 5: South and Central Americas
  FALKLAND_ISLANDS(500),
  BELIZE(501),
  GUATEMALA(502),
  EL_SALVADOR(503),
  HONDURAS(504),
  NICARAGUA(505),
  COSTA_RICA(506),
  PANAMA(507),
  SAINT_PIERRE_AND_MIQUELON(508),
  HAITI(509),
  PERU(51),
  MEXICO(52),
  CUBA(53),
  ARGENTINA(54),
  BRAZIL(55),
  CHILE(56),
  COLOMBIA(57),
  VENEZUELA(58),
  GUADELOUPE(590), // including Saint Barthelemy, Saint Martin
  BOLIVIA(591),
  GUYANA(592),
  ECUADOR(593),
  FRENCH_GUIANA(594),
  PARAGUAY(595),
  MARTINIQUE(596), // formerly assigned to Peru, now 51
  SURINAME(597),
  URUGUAY(598),
  SINT_EUSTATIUS(5993),
  SABA(5994),
  //5995 – unassigned (formerly assigned to Sint Maarten, now included in NANP as 1 (721))
  BONAIRE(5997),
  //5998 - unassigned (formerly assigned to Aruba, now at 297)
  CURACAO(5999),

  // Zone 6: Southeast Asia and Oceania
  MALAYSIA(60),
  AUSTRALIA(61),
  INDONESIA(62),
  PHILIPPINES(63),
  NEW_ZEALAND(64),
  SINGAPORE(65),
  THAILAND(66),
  EAST_TIMOR(670), // formerly 62/39 during the Indonesian occupation; formerly assigned to Northern Mariana Islands, now part of NANP as 1 (670)
  //671 – unassigned (formerly assigned to Guam, now part of NANP as 1 (671))
  AUSTRALIAN_EXTERNAL_TERRITORIES(672),
  BRUNEI(673),
  NAURU(674),
  PAPUA_NEW_GUINEA(675),
  TONGA(676),
  SOLOMON_ISLANDS(677),
  VANUATU(678),
  FIJI(679),
  PALAU(680),
  WALLIS_AND_FUTUNA(681),
  COOK_ISLANDS(682),
  NIUE(683),
  //684 – unassigned (formerly assigned to American Samoa, now part of NANP as 1 (684))
  SAMOA(685),
  KIRIBATI(686),
  NEW_CALEDONIA(687),
  TUVALU(688),
  FRENCH_POLYNESIA(689),
  TOKELAU(690),
  FEDERATED_STATES_OF_MICRONESIA(691),
  MARSHALL_ISLANDS(692),
  //693-699 – unassigned

  // Zone 7: Russia and neighboring regions
  RUSSIA(7),

  // Zone 8: East Asia, South Asia, and special services
  UNIVERSAL_INTERNATIONAL_FREEPHONE_SERVICE(800),
  //801-807 – unassigned
  UNIVERSAL_INTERNATIONAL_SHARED_COST_NUMBERS(808),
  //809 – unassigned
  JAPAN(81),
  SOUTH_KOREA(82),
  //83x – unassigned (reserved for country code expansion)[1]
  VIETNAM(84),
  NORTH_KOREA(850),
  //851 – unassigned
  HONG_KONG(852),
  MACAU(853),
  //854 – unassigned
  CAMBODIA(855),
  LAOS(856),
  //857-858 – unassigned (formerly assigned to ANAC satellite service)
  //859 – unassigned
  CHINA(86),
  GLOBAL_MOBILE_SATELLITE_SYSTEM_INMARSAT(870),
  //871 – unassigned (formerly assigned to Inmarsat Atlantic East, discontinued in 2008)
  //872 – unassigned (formerly assigned to Inmarsat Pacific, discontinued in 2008)
  //873 – unassigned (formerly assigned to Inmarsat Indian, discontinued in 2008)
  //874 – unassigned (formerly assigned to Inmarsat Atlantic West, discontinued in 2008)
  //875-877 – unassigned (reserved for future maritime mobile service)
  //878 – unassigned (formerly used for Universal Personal Telecommunications Service, discontinued in 2022)
  //879 – unassigned (reserved for national non-commercial purposes)
  BANGLADESH(880),
  GLOBAL_MOBILE_SATELLITE_SYSTEM(881),
  INTERNATIONAL_NETWORKS(882),
  INTERNATIONAL_NETWORKS_2(883),
  //884-885 – unassigned
  TAIWAN(886),
  //887 – unassigned
  //888 – unassigned (formerly assigned to OCHA for Telecommunications for Disaster Relief service)
  //889 – unassigned
  //89x – unassigned (reserved for country code expansion)

  // Zone 9: West, Central, and South Asia, and part of Eastern Europe
  TURKEY(90),
  INDIA(91),
  PAKISTAN(92),
  AFGHANISTAN(93),
  SRI_LANKA(94),
  MYANMAR(95),
  MALDIVES(960),
  LEBANON(961),
  JORDAN(962),
  SYRIA(963),
  IRAQ(964),
  KUWAIT(965),
  SAUDI_ARABIA(966),
  YEMEN(967),
  OMAN(968),
  //969 – unassigned (formerly assigned to South Yemen until its unification with North Yemen, now part of 967 Yemen)
  PALESTINE(970), //(interchangeably with 972)
  UNITED_ARAB_EMIRATES(971),
  ISRAEL(972), //(also  Palestine, interchangeably with 970)
  BAHRAIN(973),
  QATAR(974),
  BHUTAN(975),
  MONGOLIA(976),
  NEPAL(977),
  //978 – unassigned (formerly assigned to Dubai, now part of 971 United Arab Emirates)
  UNIVERSAL_INTERNATIONAL_PREMIUM_RATE_SERVICE(979), //(formerly assigned to Abu Dhabi, now part of 971 United Arab Emirates)
  IRAN(98),
  //990 – unassigned
  //991 – unassigned (formerly used for International Telecommunications Public Correspondence Service)
  TAJIKISTAN(992),
  TURKMENISTAN(993),
  AZERBAIJAN(994),
  GEORGIA(995),
  KYRGYZSTAN(996),
  KAZAKHSTAN(997), //(reserved but abandoned in November 2023; uses 7 (6xx, 7xx))
  UZBEKISTAN(998);
  //999 – unassigned (reserved for future global service)

  private final Integer code;

  CountryCallingCode(Integer code) {
    this.code = code;
  }

  public Integer getCode() {
    return this.code;
  }
}
