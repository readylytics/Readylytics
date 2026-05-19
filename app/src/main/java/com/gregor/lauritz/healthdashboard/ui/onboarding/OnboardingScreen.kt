package com.gregor.lauritz.healthdashboard.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gregor.lauritz.healthdashboard.data.preferences.PhysiologyProfile
import com.gregor.lauritz.healthdashboard.ui.components.PhysiologyProfilePicker
import com.gregor.lauritz.healthdashboard.ui.components.SettingsToggleItem
import java.time.LocalDate

@Composable
fun OnboardingScreen(
    onGrantPermissionsClick: (
        birthDay: Int,
        birthMonth: Int,
        birthYear: Int,
        gender: String,
        physiologyProfile: PhysiologyProfile,
        dynamicColorEnabled: Boolean,
    ) -> Unit,
    onOpenSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var step by remember { mutableIntStateOf(0) }

    Surface(modifier = modifier.fillMaxSize()) {
        if (step == 0) {
            WelcomeScreen(onNext = { step = 1 })
        } else {
            ProfileSetupScreen(
                onGrantPermissionsClick = onGrantPermissionsClick,
                onOpenSettingsClick = onOpenSettingsClick,
            )
        }
    }
}

@Composable
private fun WelcomeScreen(onNext: () -> Unit) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Favorite,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary,
        )

        Spacer(Modifier.height(24.dp))

        Text(
            text = "Welcome to Readylytics",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Your personal recovery & readiness tracker — powered entirely by your own data.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(32.dp))

        FeatureItem(
            icon = { Icon(Icons.Filled.Favorite, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            title = "Sleep Score",
            description = "Weighted by duration, architecture, and restoration quality.",
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        FeatureItem(
            icon = {
                Icon(
                    Icons.Filled.FavoriteBorder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                )
            },
            title = "HRV & Resting HR Tracking",
            description = "Personal baselines calculated from your last 30 days of data.",
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        FeatureItem(
            icon = {
                Icon(
                    Icons.Filled.Notifications,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                )
            },
            title = "Training Load Index",
            description = "Acute vs. chronic workload ratio to guide your recovery.",
        )

        Spacer(Modifier.height(32.dp))

        Text(
            text = "All data stays on your device. Nothing is ever uploaded to any server.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(32.dp))

        Button(
            onClick = onNext,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Get Started")
        }
    }
}

@Composable
private fun FeatureItem(
    icon: @Composable () -> Unit,
    title: String,
    description: String,
) {
    ListItem(
        headlineContent = { Text(title, style = MaterialTheme.typography.titleSmall) },
        supportingContent = { Text(description, style = MaterialTheme.typography.bodySmall) },
        leadingContent = icon,
    )
}

@Composable
private fun ProfileSetupScreen(
    onGrantPermissionsClick: (
        birthDay: Int,
        birthMonth: Int,
        birthYear: Int,
        gender: String,
        physiologyProfile: PhysiologyProfile,
        dynamicColorEnabled: Boolean,
    ) -> Unit,
    onOpenSettingsClick: () -> Unit,
) {
    var birthDay by remember { mutableStateOf(LocalDate.now().dayOfMonth.toString()) }
    var birthMonth by remember { mutableStateOf(LocalDate.now().monthValue.toString()) }
    var birthYear by remember { mutableStateOf((LocalDate.now().year - 30).toString()) }
    var gender by remember { mutableStateOf("Other") }
    var physiologyProfile by remember { mutableStateOf(PhysiologyProfile.GENERAL) }
    var dynamicColorEnabled by remember { mutableStateOf(true) }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Your Profile",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text =
                "Your birthday, gender, and activity profile are used to calculate your max heart rate and " +
                    "personalize scores. They are stored only on your device.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(20.dp))

        Text(
            text = "Activity Profile",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))

        PhysiologyProfilePicker(
            selectedProfile = physiologyProfile,
            onProfileSelected = { physiologyProfile = it },
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = "Date of Birth",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = birthDay,
                onValueChange = { birthDay = it.filter { c -> c.isDigit() }.take(2) },
                label = { Text("Day") },
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
            )
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = birthMonth,
                onValueChange = { birthMonth = it.filter { c -> c.isDigit() }.take(2) },
                label = { Text("Month") },
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
            )
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = birthYear,
                onValueChange = { birthYear = it.filter { c -> c.isDigit() }.take(4) },
                label = { Text("Year") },
                modifier = Modifier.weight(1.4f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
            )
        }

        Spacer(Modifier.height(16.dp))

        Text(
            text = "Gender",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = gender == "Male", onClick = { gender = "Male" })
            Text("Male", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(8.dp))
            RadioButton(selected = gender == "Female", onClick = { gender = "Female" })
            Text("Female", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(8.dp))
            RadioButton(selected = gender == "Other", onClick = { gender = "Other" })
            Text("Other", style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(Modifier.height(16.dp))

        Text(
            text = "Appearance",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))

        SettingsToggleItem(
            label = "Dynamic Color",
            description = "Use colors derived from your wallpaper (Android 12+)",
            checked = dynamicColorEnabled,
            onCheckedChange = { dynamicColorEnabled = it },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(24.dp))

        Text(
            text = "Health Connect Permissions",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text =
                "This app needs access to Sleep, Heart Rate, HRV, and Exercise data from Health Connect " +
                    "to calculate your scores.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(24.dp))

        val dayInt = birthDay.toIntOrNull()
        val monthInt = birthMonth.toIntOrNull()
        val yearInt = birthYear.toIntOrNull()
        val isInputValid =
            dayInt in 1..31 &&
                monthInt in 1..12 &&
                yearInt != null &&
                yearInt in 1900..LocalDate.now().year

        Button(
            onClick = {
                onGrantPermissionsClick(
                    dayInt ?: 1,
                    monthInt ?: 1,
                    yearInt ?: 1990,
                    gender,
                    physiologyProfile,
                    dynamicColorEnabled,
                )
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = isInputValid,
        ) {
            Text("Grant Access & Continue")
        }

        Spacer(Modifier.height(12.dp))

        TextButton(onClick = onOpenSettingsClick) {
            Text("Open Health Connect Settings")
        }
    }
}
