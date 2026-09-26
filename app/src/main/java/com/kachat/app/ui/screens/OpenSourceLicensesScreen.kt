package com.kachat.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kachat.app.ui.theme.LocalAppColors

/**
 * Settings > Profile > About > Open Source Licenses. Direct port of iOS's
 * `OpenSourceLicensesView` (f656d9d).
 *
 * WebRTC and the Opus codec inside it are BSD 3-Clause and require their notice to travel with
 * the binary; Protocol Buffers is BSD 3-Clause too. The Apache and MIT libraries ask for
 * attribution. None of it was anywhere in the app. Android's list differs from iOS's because the
 * stack does — gRPC-Java and bitcoinj here, grpc-swift and P256K there — and every licence below
 * was read off the library's own POM or its published LICENSE rather than assumed.
 *
 * ML Kit and Firebase are deliberately absent: they ship under Google's SDK terms, not an open
 * source licence, so they are not an attribution notice. Same scope as iOS's screen.
 */
private data class LicenseNotice(
    val name: String,
    val license: String,
    val holder: String,
    val text: String,
)

private const val BSD_3 = """Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.
3. Neither the name of the copyright holder nor the names of its contributors may be used to endorse or promote products derived from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE."""

private const val APACHE_2 = """Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License. You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and limitations under the License."""

private const val MIT = """Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE."""

private val NOTICES = listOf(
    LicenseNotice(
        "WebRTC",
        "BSD 3-Clause",
        "Copyright (c) 2011, The WebRTC project authors. All rights reserved.",
        BSD_3,
    ),
    LicenseNotice(
        "Opus",
        "BSD 3-Clause",
        "Copyright 2001-2011 Xiph.Org, Skype Limited, Octasic, Jean-Marc Valin, Timothy B. Terriberry, " +
            "CSIRO, Gregory Maxwell, Mark Borgerding, Erik de Castro Lopo",
        BSD_3,
    ),
    LicenseNotice(
        "Protocol Buffers",
        "BSD 3-Clause",
        "Copyright 2008 Google Inc. All rights reserved.",
        BSD_3,
    ),
    LicenseNotice(
        "AndroidX, Jetpack Compose and Material Components",
        "Apache License 2.0",
        "Copyright The Android Open Source Project",
        APACHE_2,
    ),
    LicenseNotice(
        "Kotlin and kotlinx.coroutines",
        "Apache License 2.0",
        "Copyright JetBrains s.r.o. and the Kotlin Programming Language contributors",
        APACHE_2,
    ),
    LicenseNotice(
        "OkHttp, Okio and Retrofit",
        "Apache License 2.0",
        "Copyright Square, Inc.",
        APACHE_2,
    ),
    LicenseNotice(
        "gRPC",
        "Apache License 2.0",
        "Copyright The gRPC Authors",
        APACHE_2,
    ),
    LicenseNotice(
        "Gson, Dagger and Hilt",
        "Apache License 2.0",
        "Copyright Google Inc.",
        APACHE_2,
    ),
    LicenseNotice(
        "bitcoinj",
        "Apache License 2.0",
        "Copyright the bitcoinj authors",
        APACHE_2,
    ),
    LicenseNotice(
        "ZXing",
        "Apache License 2.0",
        "Copyright the ZXing authors",
        APACHE_2,
    ),
    LicenseNotice(
        "Coil",
        "Apache License 2.0",
        "Copyright Coil Contributors",
        APACHE_2,
    ),
    LicenseNotice(
        "Haze",
        "Apache License 2.0",
        "Copyright Chris Banes",
        APACHE_2,
    ),
    LicenseNotice(
        "kotlin-bip39",
        "MIT",
        "Copyright (c) Electric Coin Company and the kotlin-bip39 authors",
        MIT,
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenSourceLicensesScreen(onBack: () -> Unit) {
    val colors = LocalAppColors.current
    Scaffold(
        containerColor = colors.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "Open Source Licenses",
                        color = colors.textPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = colors.textPrimary,
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = colors.background),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Text(
                    "KaChat is built with these open source libraries. Their authors ask that these " +
                        "notices travel with the app.",
                    color = colors.textSecondary,
                    fontSize = 14.sp,
                )
            }
            items(NOTICES) { notice ->
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = colors.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                notice.name,
                                color = colors.textPrimary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.height(0.dp))
                            Text(
                                notice.license,
                                color = colors.textSecondary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 11.sp,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(notice.holder, color = colors.textSecondary, fontSize = 11.sp)
                        Spacer(Modifier.height(8.dp))
                        Text(notice.text, color = colors.textSecondary, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}
