package com.healthfit.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.regex.Pattern

data class ActivityItem(
    val type: String, val start: String, val duration: String,
    val distance: String, val calories: String, val heartRate: String
)

data class LabResult(
    val marker: String, val value: String, val unit: String, val reference: String
)


data class SavedMeasurement(
    val id: Long,
    val date: String,
    val weight: String,
    val waist: String,
    val abdomen: String,
    val hip: String,
    val chest: String
)

data class SavedExam(
    val id: Long,
    val date: String,
    val fileName: String,
    val markerCount: Int
)

class HealthFitDb(context: Context) : SQLiteOpenHelper(context, "healthfit.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE activities(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                start TEXT NOT NULL,
                type TEXT NOT NULL,
                duration_minutes INTEGER NOT NULL,
                distance_km REAL,
                calories REAL,
                avg_heart_rate REAL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE measurements(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                date TEXT NOT NULL,
                weight TEXT,
                waist TEXT,
                abdomen TEXT,
                hip TEXT,
                chest TEXT
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE exams(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                date TEXT NOT NULL,
                file_name TEXT NOT NULL,
                marker_count INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE lab_results(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                exam_id INTEGER NOT NULL,
                marker TEXT NOT NULL,
                value TEXT,
                unit TEXT,
                reference TEXT
            )
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS activities(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    start TEXT NOT NULL,
                    type TEXT NOT NULL,
                    duration_minutes INTEGER NOT NULL,
                    distance_km REAL,
                    calories REAL,
                    avg_heart_rate REAL
                )
            """.trimIndent())
        }
    }

    fun saveActivities(items: List<ActivityItem>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            items.forEach { a ->
                val exists = db.rawQuery(
                    "SELECT id FROM activities WHERE start=? AND type=? LIMIT 1",
                    arrayOf(a.start, a.type)
                ).use { it.moveToFirst() }
                if (!exists) {
                    val duration = a.duration.filter { it.isDigit() }.toIntOrNull() ?: 0
                    val distance = a.distance.replace(",", ".").filter { it.isDigit() || it == '.' }.toDoubleOrNull()
                    val calories = a.calories.filter { it.isDigit() }.toDoubleOrNull()
                    val hr = a.heartRate.filter { it.isDigit() }.toDoubleOrNull()
                    db.insert("activities", null, android.content.ContentValues().apply {
                        put("start", a.start)
                        put("type", a.type)
                        put("duration_minutes", duration)
                        if (distance != null) put("distance_km", distance)
                        if (calories != null) put("calories", calories)
                        if (hr != null) put("avg_heart_rate", hr)
                    })
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun allActivities(): List<ActivityItem> =
        readableDatabase.rawQuery(
            "SELECT type,start,duration_minutes,distance_km,calories,avg_heart_rate FROM activities ORDER BY start DESC", null
        ).use { c ->
            val out = mutableListOf<ActivityItem>()
            while (c.moveToNext()) {
                out += ActivityItem(
                    c.getString(0),
                    c.getString(1),
                    "${c.getInt(2)} min",
                    if (c.isNull(3)) "—" else String.format(java.util.Locale.US, "%.2f km", c.getDouble(3)),
                    if (c.isNull(4)) "—" else "${c.getDouble(4).toInt()} kcal",
                    if (c.isNull(5)) "—" else "${c.getDouble(5).toInt()} bpm"
                )
            }
            out
        }

    fun saveMeasurement(m: SavedMeasurement) {
        writableDatabase.insert("measurements", null, android.content.ContentValues().apply {
            put("date", m.date); put("weight", m.weight); put("waist", m.waist)
            put("abdomen", m.abdomen); put("hip", m.hip); put("chest", m.chest)
        })
    }

    fun latestMeasurement(): SavedMeasurement? =
        readableDatabase.rawQuery(
            "SELECT id,date,weight,waist,abdomen,hip,chest FROM measurements ORDER BY id DESC LIMIT 1", null
        ).use { c ->
            if (!c.moveToFirst()) null else SavedMeasurement(
                c.getLong(0), c.getString(1), c.getString(2) ?: "", c.getString(3) ?: "",
                c.getString(4) ?: "", c.getString(5) ?: "", c.getString(6) ?: ""
            )
        }

    fun saveExam(fileName: String, results: List<LabResult>): Long {
        val id = writableDatabase.insert("exams", null, android.content.ContentValues().apply {
            put("date", java.time.Instant.now().toString())
            put("file_name", fileName)
            put("marker_count", results.size)
        })
        results.forEach { r ->
            writableDatabase.insert("lab_results", null, android.content.ContentValues().apply {
                put("exam_id", id); put("marker", r.marker); put("value", r.value)
                put("unit", r.unit); put("reference", r.reference)
            })
        }
        return id
    }

    fun latestExam(): SavedExam? =
        readableDatabase.rawQuery(
            "SELECT id,date,file_name,marker_count FROM exams ORDER BY id DESC LIMIT 1", null
        ).use { c ->
            if (!c.moveToFirst()) null else SavedExam(
                c.getLong(0), c.getString(1), c.getString(2), c.getInt(3)
            )
        }
}

class MainActivity : ComponentActivity() {
    private lateinit var healthClient: HealthConnectClient
    private lateinit var db: HealthFitDb
    private val permissions = setOf(
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)
        db = HealthFitDb(this)
        healthClient = HealthConnectClient.getOrCreate(this)
        setContent { MaterialTheme { HealthFitApp(healthClient, permissions, db) } }
    }
}

@Composable
fun HealthFitApp(client: HealthConnectClient, permissions: Set<String>, db: HealthFitDb) {
    var tab by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    var activities by remember { mutableStateOf<List<ActivityItem>>(emptyList()) }
    var status by remember { mutableStateOf("Pronto para sincronizar") }
    var weight by remember { mutableStateOf("") }
    var labs by remember { mutableStateOf<List<LabResult>>(emptyList()) }
    var pdfName by remember { mutableStateOf<String?>(null) }
    var measurement by remember { mutableStateOf<SavedMeasurement?>(null) }
    var savedExam by remember { mutableStateOf<SavedExam?>(null) }
    LaunchedEffect(Unit) {
        measurement = db.latestMeasurement()
        savedExam = db.latestExam()
        activities = db.allActivities()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { status = "Permissões atualizadas. Toque em sincronizar." }

    suspend fun sync() {
        status = "Sincronizando atividades e métricas..."
        try {
            val granted = client.permissionController.getGrantedPermissions()
            if (!granted.containsAll(permissions)) {
                permissionLauncher.launch(permissions)
                status = "Autorize as permissões no Health Connect."
                return
            }
            val end = Instant.now()
            val start = end.minus(30, ChronoUnit.DAYS)
            val sessions = readAll(client, ExerciseSessionRecord::class, start, end)
                .sortedByDescending { it.startTime }

            activities = sessions.map { session ->
                val s = session.startTime
                val e = session.endTime
                val calories = readAll(client, ActiveCaloriesBurnedRecord::class, s, e)
                    .filter { it.startTime < e && it.endTime > s }
                    .sumOf { it.energy.inKilocalories }
                val distance = readAll(client, DistanceRecord::class, s, e)
                    .filter { it.startTime < e && it.endTime > s }
                    .sumOf { it.distance.inMeters }
                val samples = readAll(client, HeartRateRecord::class, s, e).flatMap { it.samples }
                val avgHr = if (samples.isEmpty()) null else samples.map { it.beatsPerMinute.toDouble() }.average()

                ActivityItem(
                    exerciseName(session.exerciseType),
                    s.toString().replace("T", " ").take(16),
                    "${Duration.between(s, e).toMinutes()} min",
                    if (distance > 0) String.format(Locale.US, "%.2f km", distance / 1000.0) else "—",
                    if (calories > 0) "${calories.toInt()} kcal" else "—",
                    avgHr?.let { "${it.toInt()} bpm" } ?: "—"
                )
            }
            db.saveActivities(activities)
            activities = db.allActivities()
            status = "${activities.size} atividade(s) no histórico"
        } catch (e: Exception) {
            status = "Falha: ${e.message ?: "verifique o Health Connect"}"
        }
    }

    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            pdfName = uri.lastPathSegment ?: "exame.pdf"
            scope.launch {
                status = "Lendo exame..."
                labs = extractLabResults(contentResolver, uri)
                db.saveExam(pdfName ?: "exame.pdf", labs)
                savedExam = db.latestExam()
                status = if (labs.isEmpty()) {
                    "PDF lido, mas não foram encontrados marcadores no formato esperado."
                } else {
                    "${labs.size} marcador(es) identificado(s)"
                }
            }
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                listOf("Início","Atividades","Treinos","Saúde","IA").forEachIndexed { i, title ->
                    NavigationBarItem(
                        selected = tab == i, onClick = { tab = i },
                        icon = { Text(listOf("⌂","🏃","📅","🧪","✦")[i]) },
                        label = { Text(title) }
                    )
                }
            }
        }
    ) { padding ->
        when (tab) {
            0 -> Dashboard(activities, status, { scope.launch { sync() } }, Modifier.padding(padding))
            1 -> ActivitiesScreen(activities, Modifier.padding(padding))
            2 -> Training(Modifier.padding(padding))
            3 -> Health(
                weight = weight,
                onWeightChange = { weight = it },
                onSave = {
                    db.saveMeasurement(
                        SavedMeasurement(
                            0, Instant.now().toString(), weight, "", "", "", ""
                        )
                    )
                    measurement = db.latestMeasurement()
                    status = "Medição salva no banco local"
                },
                onPdf = { pdfLauncher.launch(arrayOf("application/pdf")) },
                pdfName = pdfName,
                labs = labs,
                savedExam = savedExam,
                measurement = measurement,
                modifier = Modifier.padding(padding)
            )
            4 -> AiScreen(activities, labs, Modifier.padding(padding))
        }
    }
}

suspend fun <T : Record> readAll(
    client: HealthConnectClient, type: kotlin.reflect.KClass<T>, start: Instant, end: Instant
): List<T> {
    val result = mutableListOf<T>()
    var token: String? = null
    do {
        val response = client.readRecords(
            ReadRecordsRequest(recordType = type, timeRangeFilter = TimeRangeFilter.between(start, end), pageToken = token)
        )
        result += response.records
        token = response.pageToken
    } while (token != null)
    return result
}

fun exerciseName(type: Int): String = when (type) {
    ExerciseSessionRecord.EXERCISE_TYPE_RUNNING -> "Corrida"
    ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "Caminhada"
    ExerciseSessionRecord.EXERCISE_TYPE_BIKING -> "Ciclismo"
    ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING -> "Musculação"
    ExerciseSessionRecord.EXERCISE_TYPE_FUNCTIONAL_STRENGTH_TRAINING -> "Funcional"
    ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "Trilha"
    ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER,
    ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL -> "Natação"
    else -> "Atividade física"
}

suspend fun extractLabResults(resolver: android.content.ContentResolver, uri: Uri): List<LabResult> =
    withContext(Dispatchers.IO) {
        try {
            resolver.openFileDescriptor(uri, "r")?.use { pfd ->
                PDDocument.load(pfd.fileDescriptor).use { document ->
                    val text = PDFTextStripper().getText(document)
                    parseLabText(text)
                }
            } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

fun parseLabText(text: String): List<LabResult> {
    val known = listOf(
        "Glicose" to "mg/dL", "Colesterol Total" to "mg/dL", "HDL" to "mg/dL",
        "LDL" to "mg/dL", "Triglicerídeos" to "mg/dL", "Hemoglobina Glicada" to "%",
        "TSH" to "µUI/mL", "T4 Livre" to "ng/dL", "Vitamina D" to "ng/mL",
        "Ferritina" to "ng/mL", "Hemoglobina" to "g/dL"
    )
    val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
    val out = mutableListOf<LabResult>()
    for ((name, defaultUnit) in known) {
        val line = lines.firstOrNull { it.contains(name, ignoreCase = true) } ?: continue
        val m = Pattern.compile("""([0-9]+(?:[.,][0-9]+)?)""").matcher(line)
        if (m.find()) {
            val value = m.group(1) ?: continue
            val normalized = value.replace(',', '.')
            out += LabResult(name, normalized, defaultUnit, "ver laudo")
        }
    }
    return out.distinctBy { it.marker }
}

data class ChartPoint(val label: String, val value: Float)

@Composable
fun MiniLineChart(points: List<ChartPoint>, modifier: Modifier = Modifier) {
    if (points.isEmpty()) return
    val max = points.maxOf { it.value }.coerceAtLeast(1f)
    val min = points.minOf { it.value }
    val range = (max - min).coerceAtLeast(1f)
    Canvas(modifier = modifier.fillMaxWidth().height(150.dp)) {
        val step = if (points.size == 1) 0f else size.width / (points.size - 1)
        val coords = points.mapIndexed { i,p -> Offset(i*step, size.height-((p.value-min)/range)*(size.height-20f)-10f) }
        for (i in 0 until coords.lastIndex) drawLine(Color(0xFF1769E0), coords[i], coords[i+1], strokeWidth=5f)
        coords.forEach { drawCircle(Color(0xFF1769E0), 7f, it) }
    }
}

@Composable
fun Dashboard(activities: List<ActivityItem>, status: String, onSync: () -> Unit, modifier: Modifier = Modifier) {
    val minutes=activities.sumOf{it.duration.filter(Char::isDigit).toIntOrNull()?:0}
    val calories=activities.sumOf{it.calories.filter(Char::isDigit).toIntOrNull()?:0}
    val distance=activities.sumOf{it.distance.replace(",",".").filter{c->c.isDigit()||c=='.'}.toDoubleOrNull()?:0.0}
    val points=activities.take(7).reversed().mapIndexed{i,x->ChartPoint("${i+1}",x.duration.filter(Char::isDigit).toFloatOrNull()?:0f)}
    LazyColumn(modifier.padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{Spacer(Modifier.height(16.dp));Text("HealthFit",style=MaterialTheme.typography.headlineSmall);Text("Seu painel de saúde e performance",style=MaterialTheme.typography.titleLarge)}
        item{Button(onClick=onSync,modifier=Modifier.fillMaxWidth()){Text("Sincronizar Health Connect")};Text(status,style=MaterialTheme.typography.labelSmall)}
        item{Row(horizontalArrangement=Arrangement.spacedBy(7.dp),modifier=Modifier.fillMaxWidth()){Stat("Treinos",activities.size.toString());Stat("Tempo","$minutes min");Stat("Calorias","$calories")}}
        item{Card(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp)){Text("Volume de treino",style=MaterialTheme.typography.titleMedium);Text("Duração das últimas atividades",style=MaterialTheme.typography.labelSmall);MiniLineChart(points)}}}
        item{Card(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp)){Text("Resumo do período",style=MaterialTheme.typography.titleMedium);Text("⏱ $minutes min");Text("🔥 $calories kcal");Text("📏 ${String.format(Locale.US,"%.2f",distance)} km")}}}
        item{Text("Treino de hoje",style=MaterialTheme.typography.titleMedium)}
        item{Card(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp)){Text("🏃 Corrida — 5 km",style=MaterialTheme.typography.titleMedium);Text("30–35 min • intensidade moderada")}}}
        item{Text("Últimas atividades",style=MaterialTheme.typography.titleMedium)}
        if(activities.isEmpty()) item{Text("Sincronize para carregar seus treinos.")} else items(activities.take(5)){ActivityRow(it)}
    }
}

@Composable fun Stat(label: String, value: String) {
    Card(Modifier.weight(1f)) { Column(Modifier.padding(14.dp)) { Text(label, style = MaterialTheme.typography.labelSmall); Text(value, style = MaterialTheme.typography.titleMedium) } }
}
@Composable fun ActivitiesScreen(items: List<ActivityItem>, modifier: Modifier = Modifier) {
    LazyColumn(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Histórico de atividades", style = MaterialTheme.typography.headlineSmall) }
        if (items.isEmpty()) item { Text("Nenhuma atividade sincronizada.") } else items(items) { ActivityRow(it) }
    }
}
@Composable fun ActivityRow(item: ActivityItem) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(15.dp)) {
        Text("🏃 ${item.type}", style = MaterialTheme.typography.titleMedium)
        Text(item.start, style = MaterialTheme.typography.labelSmall)
        Text("${item.duration} • ${item.distance} • ${item.calories}")
        Text("FC média: ${item.heartRate}", style = MaterialTheme.typography.labelSmall)
    }}
}
@Composable
fun Training(modifier: Modifier = Modifier) {
    val planned=listOf("SEG • 10/08" to "🏃 Corrida 5 km","TER • 11/08" to "🏋️ Musculação 40 min","QUA • 12/08" to "🏃 Corrida 5 km","QUI • 13/08" to "🏋️ Musculação 40 min","SEX • 14/08" to "🏃 Corrida 5 km","SÁB • 15/08" to "🏋️ Funcional 40 min","DOM • 16/08" to "😴 Descanso")
    LazyColumn(modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
        item{Text("Calendário de treinos",style=MaterialTheme.typography.headlineSmall)}
        items(planned){(day,workout)->Card(Modifier.fillMaxWidth()){Column(Modifier.padding(15.dp)){Text(day,style=MaterialTheme.typography.labelSmall);Text(workout,style=MaterialTheme.typography.titleMedium);if(day.contains("10/08")||day.contains("11/08"))Text("✓ Realizado",color=Color(0xFF1E9E61),style=MaterialTheme.typography.labelSmall)else if(day.contains("12/08"))Text("● Hoje",color=Color(0xFF1769E0),style=MaterialTheme.typography.labelSmall)}}}
    }
}

@Composable
fun Health(
    weight: String,
    onWeightChange: (String) -> Unit,
    onSave: () -> Unit,
    onPdf: () -> Unit,
    pdfName: String?,
    labs: List<LabResult>,
    savedExam: SavedExam?,
    measurement: SavedMeasurement?,
    modifier: Modifier = Modifier
) {
    LazyColumn(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Saúde", style = MaterialTheme.typography.headlineSmall) }
        item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
            Text("Peso e medidas", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(weight, onWeightChange, label = { Text("Peso (kg)") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp)); Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) { Text("Salvar medição") }
            if (measurement != null) Text("Última medição: ${measurement.date.take(10)} • ${measurement.weight} kg", style = MaterialTheme.typography.labelSmall)
        }}}
        item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
            Text("Exames", style = MaterialTheme.typography.titleMedium)
            Text("Importe um PDF de laboratório. A V1.4 extrai texto de PDFs digitais e tenta identificar marcadores comuns.")
            Spacer(Modifier.height(8.dp)); OutlinedButton(onClick = onPdf, modifier = Modifier.fillMaxWidth()) { Text("Selecionar PDF") }
            if (pdfName != null) Text("Arquivo: $pdfName", style = MaterialTheme.typography.labelSmall)
            if (savedExam != null) Text("Salvo no banco: ${savedExam.fileName} • ${savedExam.markerCount} marcador(es)", style = MaterialTheme.typography.labelSmall)
        }}}
        if (labs.isNotEmpty()) {
            item { Text("Resultados identificados", style = MaterialTheme.typography.titleMedium) }
            items(labs) { lab -> Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Column { Text(lab.marker, style = MaterialTheme.typography.titleMedium); Text(lab.reference, style = MaterialTheme.typography.labelSmall) }
                Text("${lab.value} ${lab.unit}", style = MaterialTheme.typography.titleMedium)
            }}}
        }
    }
}

@Composable
fun AiScreen(activities: List<ActivityItem>, labs: List<LabResult>, modifier: Modifier = Modifier) {
    val minutes=activities.sumOf{it.duration.filter(Char::isDigit).toIntOrNull()?:0}
    LazyColumn(modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{Text("Assistente IA",style=MaterialTheme.typography.headlineSmall)}
        item{Card(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp)){Text("HealthFit AI",style=MaterialTheme.typography.titleLarge);Text("🏃 ${activities.size} atividades");Text("⏱ $minutes minutos");Text("🧪 ${labs.size} marcadores laboratoriais");Spacer(Modifier.height(8.dp));Text("Próxima camada: backend seguro de IA para cruzar histórico de treinos, peso, medidas e exames.")}}}
        item{Card(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp)){Text("Segurança",style=MaterialTheme.typography.titleMedium);Text("Chaves de API não serão armazenadas no aplicativo. A integração deverá ocorrer por backend autenticado.")}}}
    }
}
