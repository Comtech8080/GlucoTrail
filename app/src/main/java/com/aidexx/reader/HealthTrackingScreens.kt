package com.aidexx.reader

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.aidexx.reader.data.*
import java.text.DateFormat
import java.util.*
import kotlin.math.roundToInt

private val healthRanges = listOf(7,14,30,60,90,180)
private fun healthDate(ms:Long)=DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT).format(Date(ms))

@Composable private fun HealthRangeRow(selected:Int,onSelect:(Int)->Unit){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(3.dp)){healthRanges.forEach{d->FilterChip(selected=selected==d,onClick={onSelect(d)},label={Text("${d}d")},modifier=Modifier.weight(1f))}}}

@Composable private fun MetricChart(a:List<Pair<Long,Float>>,b:List<Pair<Long,Float>> = emptyList()) { val primary=MaterialTheme.colorScheme.primary; val secondary=MaterialTheme.colorScheme.tertiary; Card(Modifier.fillMaxWidth()){ if(a.isEmpty()) Box(Modifier.fillMaxWidth().height(150.dp).padding(16.dp)){Text("No readings in this period") } else Canvas(Modifier.fillMaxWidth().height(170.dp).padding(12.dp)){val all=(a+b).map{it.second};val min=(all.minOrNull()?:0f)-2;val max=(all.maxOrNull()?:1f)+2;val t0=(a+b).minOf{it.first};val t1=(a+b).maxOf{it.first}.coerceAtLeast(t0+1);fun path(data:List<Pair<Long,Float>>):Path{val p=Path();data.forEachIndexed{i,v->val x=((v.first-t0).toFloat()/(t1-t0))*size.width;val y=size.height-((v.second-min)/(max-min))*size.height;if(i==0)p.moveTo(x,y)else p.lineTo(x,y)};return p};drawPath(path(a),primary,style=androidx.compose.ui.graphics.drawscope.Stroke(4f));if(b.isNotEmpty())drawPath(path(b),secondary,style=androidx.compose.ui.graphics.drawscope.Stroke(4f)) } } }

@Composable internal fun BloodPressureEditor(existing:BloodPressureEntry?,dismiss:()->Unit,save:(BloodPressureEntry)->Unit,delete:(BloodPressureEntry)->Unit){var sys by remember{mutableStateOf(existing?.systolic?.toString() ?: "")};var dia by remember{mutableStateOf(existing?.diastolic?.toString() ?: "")};var pulse by remember{mutableStateOf(existing?.pulse?.toString() ?: "")};var note by remember{mutableStateOf(existing?.note?:"")};var time by remember{mutableLongStateOf(existing?.timestampMs?:System.currentTimeMillis())};AlertDialog(onDismissRequest=dismiss,title={Text(if(existing==null)"Add Blood Pressure" else "Edit Blood Pressure")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){HealthDateTime(time){time=it};OutlinedTextField(sys,{sys=it},label={Text("Systolic (mmHg)")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number));OutlinedTextField(dia,{dia=it},label={Text("Diastolic (mmHg)")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number));OutlinedTextField(pulse,{pulse=it},label={Text("Pulse (BPM, optional)")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number));OutlinedTextField(note,{note=it},label={Text("Note (optional)")});if(existing!=null)TextButton(onClick={delete(existing)}){Text("Delete")}}},confirmButton={Button(enabled=(sys.toIntOrNull() ?: 0)>0&&(dia.toIntOrNull() ?: 0)>0&&(pulse.isBlank()||(pulse.toIntOrNull() ?: 0)>0),onClick={save(BloodPressureEntry(existing?.id?:0,time,sys.toInt(),dia.toInt(),pulse.toIntOrNull(),note))}){Text("Save")}},dismissButton={TextButton(onClick=dismiss){Text("Cancel")}})}

@Composable internal fun WeightEditor(existing:WeightEntry?,pounds:Boolean,dismiss:()->Unit,save:(WeightEntry)->Unit,delete:(WeightEntry)->Unit){val factor=if(pounds)2.2046226218 else 1.0;var value by remember{mutableStateOf(existing?.let{"%.1f".format(it.weightKg*factor)}?:"")};var note by remember{mutableStateOf(existing?.note?:"")};var time by remember{mutableLongStateOf(existing?.timestampMs?:System.currentTimeMillis())};AlertDialog(onDismissRequest=dismiss,title={Text(if(existing==null)"Add Weight" else "Edit Weight")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){HealthDateTime(time){time=it};OutlinedTextField(value,{value=it},label={Text("Weight (${if(pounds)"lb" else "kg"})")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal));OutlinedTextField(note,{note=it},label={Text("Note (optional)")});if(existing!=null)TextButton(onClick={delete(existing)}){Text("Delete")}}},confirmButton={Button(enabled=value.toDoubleOrNull()?.let { it.isFinite() && it > 0.0 } == true,onClick={save(WeightEntry(existing?.id?:0,time,value.toDouble()/factor,note))}){Text("Save")}},dismissButton={TextButton(onClick=dismiss){Text("Cancel")}})}

@Composable private fun HealthDateTime(ms:Long,onChange:(Long)->Unit){val ctx=androidx.compose.ui.platform.LocalContext.current;TextButton(onClick={val c=Calendar.getInstance().apply{timeInMillis=ms};DatePickerDialog(ctx,{_,y,m,d->val n=Calendar.getInstance().apply{timeInMillis=ms;set(y,m,d)};TimePickerDialog(ctx,{_,h,min->n.set(Calendar.HOUR_OF_DAY,h);n.set(Calendar.MINUTE,min);onChange(n.timeInMillis)},c.get(Calendar.HOUR_OF_DAY),c.get(Calendar.MINUTE),false).show()},c.get(Calendar.YEAR),c.get(Calendar.MONTH),c.get(Calendar.DAY_OF_MONTH)).show()}){Text(healthDate(ms))}}
