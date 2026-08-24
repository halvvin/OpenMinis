package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.openminis.app.data.UserProfile
import com.openminis.app.data.UserProfileStore

/**
 * [T-user-profile] Settings → User Profile screen.
 *
 * The user describes themselves once; when enabled, the profile is injected
 * into every conversation's system prompt so responses are personalised to
 * their background, skills and goals. Data lives only on-device
 * (SharedPreferences, see [UserProfileStore]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserProfileScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { UserProfileStore.get(context) }

    var enabled by remember { mutableStateOf(true) }
    var name by remember { mutableStateOf("") }
    var age by remember { mutableStateOf("") }
    var occupation by remember { mutableStateOf("") }
    var field by remember { mutableStateOf("") }
    var skills by remember { mutableStateOf("") }
    var interests by remember { mutableStateOf("") }
    var goals by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val p = store.load()
        enabled = p.enabled
        name = p.name
        age = p.age
        occupation = p.occupation
        field = p.workField
        skills = p.skills
        interests = p.interests
        goals = p.goals
        notes = p.notes
        loaded = true
    }

    /** Snapshot of the current form state as a persistable profile. */
    fun buildProfile() = UserProfile(
        enabled = enabled,
        name = name.trim(),
        age = age.trim(),
        occupation = occupation.trim(),
        workField = field.trim(),
        skills = skills.trim(),
        interests = interests.trim(),
        goals = goals.trim(),
        notes = notes.trim(),
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("پروفایل کاربر") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (!loaded) return@Scaffold

        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "فعال (ارسال به مدل در ابتدای هر گفتگو)",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Switch(
                    checked = enabled,
                    onCheckedChange = {
                        enabled = it
                        // [T-profile-toggle-write-through] Persist IMMEDIATELY on
                        // toggle — previously the value only reached disk via the
                        // Save button, so leaving the screen any other way (back
                        // gesture, navigation) silently reverted the switch.
                        store.save(buildProfile())
                    },
                )
            }

            Text(
                "این اطلاعات فقط روی دستگاه تو ذخیره می‌شود و صرفاً برای شخصی‌سازی پاسخ‌ها به مدل ارسال می‌گردد.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("نام") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(value = age, onValueChange = { age = it }, label = { Text("سن") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(value = occupation, onValueChange = { occupation = it }, label = { Text("شغل") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(value = field, onValueChange = { field = it }, label = { Text("زمینه کاری / تحصیلی") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(value = skills, onValueChange = { skills = it }, label = { Text("مهارت‌ها") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
            OutlinedTextField(value = interests, onValueChange = { interests = it }, label = { Text("علایق") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
            OutlinedTextField(value = goals, onValueChange = { goals = it }, label = { Text("اهداف") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
            OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("نکات دیگر") }, modifier = Modifier.fillMaxWidth(), minLines = 2)

            Button(
                onClick = {
                    store.save(buildProfile())
                    onBack()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
            ) { Text("ذخیره") }
        }
    }
}
