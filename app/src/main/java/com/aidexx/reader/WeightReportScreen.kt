package com.aidexx.reader

import android.graphics.Paint
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aidexx.reader.data.*
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.delay

private val WeightGreen = Color(0xFF00C878)
private val WeightInk = Color(0xFFF2F4F7)
private fun weightDate(ms: Long) = DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT).format(Date(ms))
internal fun weightBmi(kg: Double, cm: Double): Double = kg / (cm / 100.0 * cm / 100.0)
// Adult screening categories: https://www.cdc.gov/bmi/adult-calculator/bmi-categories.html
internal fun weightBmiLabel(bmi: Double): String = when {
    bmi < 18.5 -> "Underweight"
    bmi < 25 -> "Healthy weight"
    bmi < 30 -> "Overweight"
    bmi < 35 -> "Obesity · Class 1"
    bmi < 40 -> "Obesity · Class 2"
    else -> "Obesity · Class 3"
}

@Composable fun WeightScreen(onBack: () -> Unit, backLabel: String = "‹ Notes", reportActive: Boolean = true, swipeHint: String? = null) {
    val reportModern = LocalTrailTheme.current.isModern
    val reportColors = MaterialTheme.colorScheme
    val reportBackground = if(reportModern) reportColors.background else Color.Black
    val reportInk = if(reportModern) reportColors.onSurface else Color(0xFFF2F4F7)
    val reportMuted = if(reportModern) reportColors.onSurfaceVariant else Color(0xFFB4BCC8)
    val reportSurface = if(reportModern) reportColors.surface else Color(0xFF17191D)
    val reportGrid = if(reportModern) reportColors.outlineVariant else Color(0xFF303640)
    val reportAccent = if(reportModern) reportColors.primary else WeightGreen

    val context = LocalContext.current
    val db = remember { AidexHistoryDatabase(context.applicationContext) }
    var entries by remember { mutableStateOf(db.loadWeights()) }
    var days by remember { mutableIntStateOf(7) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var bodyFat by remember { mutableStateOf<Pair<Double, Long>?>(null) }
    var height by remember { mutableStateOf<Double?>(null) }
    var editing by remember { mutableStateOf<WeightEntry?>(null) }
    LaunchedEffect(reportActive) {
        if(reportActive) {
            entries = db.loadWeights(); days = 7
            height = db.loadHeightCm(System.currentTimeMillis())
            bodyFat = db.loadLatestBodyFat(System.currentTimeMillis())
            while(true) { now = System.currentTimeMillis(); delay(30_000L) }
        }
    }
    DisposableEffect(db) { onDispose { db.close() } }
    BackHandler(enabled = reportActive && editing == null, onBack = onBack)
    val latest = entries.filter { it.timestampMs <= now }.maxByOrNull { it.timestampMs }
    val shown = entries.filter { it.timestampMs in (now-days*86_400_000L)..now }.sortedBy { it.timestampMs }
    MaterialTheme(colorScheme = if(LocalTrailTheme.current.isModern) MaterialTheme.colorScheme else darkColorScheme(
        primary = WeightGreen,
        onPrimary = reportBackground,
        background = reportBackground,
        onBackground = reportInk,
        surface = reportSurface,
        onSurface = reportInk,
        surfaceVariant = Color(0xFF252B2B),
        onSurfaceVariant = Color(0xFFB4C0BB)
    )) {
    LazyColumn(Modifier.fillMaxSize().background(reportBackground), contentPadding=PaddingValues(bottom=24.dp), verticalArrangement=Arrangement.spacedBy(10.dp)) {
        item {
            Column(Modifier.fillMaxWidth().background(reportBackground).padding(16.dp)) {
                TextButton(onClick=onBack,contentPadding=PaddingValues(0.dp)) { Text(backLabel,color=reportInk) }
                Text("Weight",color=reportInk,fontSize=28.sp,fontWeight=FontWeight.Medium)
                Text("Track your progress · lb",color=reportInk)
                swipeHint?.let { Text(it,color=reportInk,style=MaterialTheme.typography.labelSmall) }
            }
        }
        item { WeightCard {
            Text("Last measurement",style=MaterialTheme.typography.titleMedium)
            Text(latest?.let { "%.1f lb".format(it.weightKg*2.2046226218) } ?: "No readings yet",fontSize=36.sp,fontWeight=FontWeight.Medium)
            latest?.let {
                Text("%.1f kg".format(it.weightKg), fontSize=16.sp, color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(latest?.let { weightDate(it.timestampMs) } ?: "Add weight using Notes on the glucose screen.",style=MaterialTheme.typography.bodySmall)
        } }
        item { WeightCard {
            Text("Body fat",style=MaterialTheme.typography.titleMedium)
            val savedFat = bodyFat
            if(savedFat != null) {
                Text("%.1f%%".format(savedFat.first),fontSize=32.sp,fontWeight=FontWeight.Medium)
                Text(weightDate(savedFat.second),style=MaterialTheme.typography.bodySmall)
                Text("Latest percentage recorded from your scale",style=MaterialTheme.typography.labelSmall)
            } else {
                Text("Add your scale's body fat percentage in Notes.",style=MaterialTheme.typography.bodyMedium)
            }
        } }
        item { WeightCard {
            Text("BMI",style=MaterialTheme.typography.titleMedium)
            val cm = height
            if(latest != null && cm != null) {
                val bmi=weightBmi(latest.weightKg,cm)
                Text("%.1f · %s".format(bmi,weightBmiLabel(bmi)),fontWeight=FontWeight.Medium)
                BmiScale(bmi)
                Text("Adult screening measure · height %.1f in".format(cm/2.54),style=MaterialTheme.typography.labelSmall)
            } else {
                Text("Add your height in inches and weight in Notes to see BMI.",style=MaterialTheme.typography.bodyMedium)
                BmiScale(null)
            }
        } }
        item { WeightCard {
            Text("History chart",style=MaterialTheme.typography.titleLarge)
            var menu by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick={menu=true}) { Text("Last $days days ▾",color=WeightGreen) }
                DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
                    listOf(7,14,30,60,90,180).forEach { d -> DropdownMenuItem(text={Text("Last $d days")},onClick={days=d;menu=false}) }
                }
            }
            if(shown.isEmpty()) Text("No readings in this period") else {
                val values=shown.map { it.weightKg*2.2046226218 }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    Text("Min: %.1f".format(values.minOrNull()),style=MaterialTheme.typography.labelMedium)
                    Text("Avg: %.1f".format(values.average()),style=MaterialTheme.typography.labelMedium)
                    Text("Max: %.1f".format(values.maxOrNull()),style=MaterialTheme.typography.labelMedium)
                }
                Text("lb",style=MaterialTheme.typography.labelSmall)
                WeightGraph(shown,days,now)
            }
        } }
        item { Text("History · edit note-linked entries in Notes",modifier=Modifier.padding(horizontal=20.dp),color=reportInk,style=MaterialTheme.typography.labelMedium) }
        items(entries.sortedByDescending { it.timestampMs },key={it.id}) { entry ->
            WeightCard(Modifier.clickable(enabled=entry.id>0) { editing=entry }) {
                Text("%.1f lb".format(entry.weightKg*2.2046226218),fontWeight=FontWeight.SemiBold)
                Text("%.1f kg".format(entry.weightKg), fontSize=12.sp, color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text(weightDate(entry.timestampMs),style=MaterialTheme.typography.bodySmall)
                if(entry.note.isNotBlank()) Text(entry.note,style=MaterialTheme.typography.bodySmall)
            }
        }
    }
    editing?.let { entry -> WeightEditor(entry,true,{editing=null},{db.saveWeight(it);entries=db.loadWeights();editing=null},{db.deleteWeight(it.id);entries=db.loadWeights();editing=null}) }
    }
}

@Composable private fun WeightCard(modifier:Modifier=Modifier,content:@Composable ColumnScope.()->Unit) {
    val reportModern = LocalTrailTheme.current.isModern
    val reportColors = MaterialTheme.colorScheme
    val reportBackground = if(reportModern) reportColors.background else Color.Black
    val reportInk = if(reportModern) reportColors.onSurface else Color(0xFFF2F4F7)
    val reportMuted = if(reportModern) reportColors.onSurfaceVariant else Color(0xFFB4BCC8)
    val reportSurface = if(reportModern) reportColors.surface else Color(0xFF17191D)
    val reportGrid = if(reportModern) reportColors.outlineVariant else Color(0xFF303640)
    val reportAccent = if(reportModern) reportColors.primary else WeightGreen

    Card(Modifier.padding(horizontal=10.dp).fillMaxWidth().then(modifier),shape=RoundedCornerShape(16.dp),colors=CardDefaults.cardColors(containerColor=reportSurface,contentColor=reportInk)) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp),content=content)
    }
}

@Composable private fun BmiScale(bmi:Double?) {
    val reportModern = LocalTrailTheme.current.isModern
    val reportColors = MaterialTheme.colorScheme
    val reportBackground = if(reportModern) reportColors.background else Color.Black
    val reportInk = if(reportModern) reportColors.onSurface else Color(0xFFF2F4F7)
    val reportMuted = if(reportModern) reportColors.onSurfaceVariant else Color(0xFFB4BCC8)
    val reportSurface = if(reportModern) reportColors.surface else Color(0xFF17191D)
    val reportGrid = if(reportModern) reportColors.outlineVariant else Color(0xFF303640)
    val reportAccent = if(reportModern) reportColors.primary else WeightGreen

    Canvas(Modifier.fillMaxWidth().height(48.dp).semantics { contentDescription=bmi?.let { "BMI %.1f".format(it) } ?: "BMI requires height and weight" }) {
        val bounds=listOf(15.0,18.5,25.0,30.0,35.0,40.0,45.0)
        val colors=listOf(0xFF5BC5DF,0xFF7CDE96,0xFFFFD75E,0xFFFFAD65,0xFFFF7B77,0xFFF4649A)
        fun x(v:Double)=((v-15.0)/30.0).toFloat()*size.width
        for(i in 0..5) drawRect(Color(colors[i]),Offset(x(bounds[i]),18.dp.toPx()),Size(x(bounds[i+1])-x(bounds[i])-1.dp.toPx(),6.dp.toPx()))
        bmi?.let { drawCircle(WeightGreen,4.dp.toPx(),Offset(x(it.coerceIn(15.0,45.0)),8.dp.toPx())); drawLine(WeightGreen,Offset(x(it.coerceIn(15.0,45.0)),8.dp.toPx()),Offset(x(it.coerceIn(15.0,45.0)),18.dp.toPx()),2.dp.toPx()) }
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=reportInk.toArgb();textSize=9.sp.toPx();textAlign=Paint.Align.CENTER}
        bounds.drop(1).dropLast(1).forEach { drawContext.canvas.nativeCanvas.drawText("%.1f".format(it),x(it),40.dp.toPx(),paint) }
    }
}

@Composable private fun WeightGraph(entries:List<WeightEntry>,days:Int,now:Long) {
    val reportModern = LocalTrailTheme.current.isModern
    val reportColors = MaterialTheme.colorScheme
    val reportBackground = if(reportModern) reportColors.background else Color.Black
    val reportInk = if(reportModern) reportColors.onSurface else Color(0xFFF2F4F7)
    val reportMuted = if(reportModern) reportColors.onSurfaceVariant else Color(0xFFB4BCC8)
    val reportSurface = if(reportModern) reportColors.surface else Color(0xFF17191D)
    val reportGrid = if(reportModern) reportColors.outlineVariant else Color(0xFF303640)
    val reportAccent = if(reportModern) reportColors.primary else WeightGreen

    Canvas(Modifier.fillMaxWidth().height(220.dp).semantics {contentDescription="Weight in pounds over $days days; ${entries.size} readings. Values in history below."}) {
        val low=entries.minOf {it.weightKg*2.2046226218}-3.0
        val high=entries.maxOf {it.weightKg*2.2046226218}+3.0
        val start=now-days*86_400_000L
        val left=36.dp.toPx();val right=size.width-14.dp.toPx();val top=18.dp.toPx();val bottom=size.height-24.dp.toPx()
        fun x(t:Long)=left+((t-start).toDouble()/(now-start)).toFloat()*(right-left)
        fun y(v:Double)=bottom-((v-low)/(high-low)).toFloat()*(bottom-top)
        drawRect(reportSurface,Offset(left,top),Size(right-left,bottom-top))
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=reportInk.toArgb();textSize=10.sp.toPx()}
        for(i in 0..4) {val v=low+(high-low)*i/4;drawLine(reportGrid,Offset(left,y(v)),Offset(right,y(v)),1.dp.toPx());paint.textAlign=Paint.Align.RIGHT;drawContext.canvas.nativeCanvas.drawText("%.1f".format(v),left-3.dp.toPx(),y(v)+3.dp.toPx(),paint)}
        val format=SimpleDateFormat("MMM d",Locale.getDefault())
        for(i in 0..3) {val t=start+(now-start)*i/3;drawLine(reportGrid,Offset(x(t),top),Offset(x(t),bottom),1.dp.toPx());paint.textAlign=when(i){0->Paint.Align.LEFT;3->Paint.Align.RIGHT;else->Paint.Align.CENTER};drawContext.canvas.nativeCanvas.drawText(format.format(Date(t)),x(t),bottom+18.dp.toPx(),paint)}
        entries.zipWithNext().forEach {(a,b)->drawLine(WeightGreen,Offset(x(a.timestampMs),y(a.weightKg*2.2046226218)),Offset(x(b.timestampMs),y(b.weightKg*2.2046226218)),2.5.dp.toPx())}
        var lastX=-1000f
        entries.forEach {e->val px=x(e.timestampMs);val py=y(e.weightKg*2.2046226218);drawCircle(reportInk,4.dp.toPx(),Offset(px,py));drawCircle(WeightGreen,2.5.dp.toPx(),Offset(px,py));if(entries.size<=8&&px-lastX>30.dp.toPx()){paint.textAlign=if(px>right-15.dp.toPx())Paint.Align.RIGHT else Paint.Align.LEFT;drawContext.canvas.nativeCanvas.drawText("%.1f".format(e.weightKg*2.2046226218),px,py-8.dp.toPx(),paint);lastX=px}}
    }
}
