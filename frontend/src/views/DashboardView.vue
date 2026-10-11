<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import * as echarts from 'echarts'
import http from '../api/http'

const loading=ref(true), snapshot=ref(false), error=ref(''), analytics=ref({})
const refs=Array.from({length:6},()=>ref(null)); const charts=[]
const total=computed(()=> (analytics.value.severity||[]).reduce((sum,row)=>sum+Number(row.vuln_count||0),0))
const high=computed(()=> (analytics.value.severity||[]).filter(row=>['CRITICAL','HIGH'].includes(row.severity)).reduce((sum,row)=>sum+Number(row.vuln_count||0),0))
const packages=computed(()=>analytics.value.mavenRisk?.length||0)
const cwes=computed(()=>analytics.value.cweTop?.length||0)
const red='#e4002b', black='#111111', grey='#8d8d93', grid='#d7d7db'

async function load(){
  try{ const response=await http.get('/analytics/all'); analytics.value=response.data }
  catch(primary){
    try{ const response=await fetch('/data/analytics.json'); if(!response.ok) throw new Error(); analytics.value=await response.json(); snapshot.value=true }
    catch{ error.value='尚未取得Spark分析结果'; loading.value=false; return }
  }
  loading.value=false; await nextTick(); draw()
}
function base(title,subtitle){return{animationDuration:700,title:{text:title,subtext:subtitle,left:18,top:15,textStyle:{fontSize:16,fontWeight:700,color:black},subtextStyle:{fontSize:11,color:grey}},tooltip:{trigger:'item'},textStyle:{fontFamily:'Arial'},color:[red,black,'#76767c','#bcbcc1'],grid:{left:58,right:26,top:75,bottom:45},legend:{top:20,right:18,textStyle:{fontSize:10}}}}
function draw(){
  const a=analytics.value
  const options=[]
  options.push({...base('漏洞时间趋势','D1 · 按月发布数量'),tooltip:{trigger:'axis'},xAxis:{type:'category',data:(a.trend||[]).map(x=>String(x.stat_month).slice(0,7)),axisLabel:{color:grey}},yAxis:{type:'value',splitLine:{lineStyle:{color:grid}}},series:[{name:'漏洞数',type:'line',smooth:true,symbol:'none',lineStyle:{width:2,color:red},areaStyle:{color:'rgba(228,0,43,.11)'},data:(a.trend||[]).map(x=>x.total_count)}]})
  options.push({...base('严重度分布','D2 · 唯一漏洞占比'),series:[{type:'pie',roseType:'radius',radius:['28%','68%'],center:['50%','58%'],label:{formatter:'{b}\n{d}%'},data:(a.severity||[]).map(x=>({name:x.severity,value:x.vuln_count}))}]})
  options.push({...base('高频弱点 Top 10','D3 · CWE漏洞数量'),grid:{left:95,right:28,top:70,bottom:32},xAxis:{type:'value',splitLine:{lineStyle:{color:grid}}},yAxis:{type:'category',inverse:true,data:(a.cweTop||[]).map(x=>x.cwe_id),axisLabel:{color:black}},series:[{type:'bar',data:(a.cweTop||[]).map(x=>x.vuln_count),itemStyle:{color:red},barWidth:14}]})
  options.push({...base('Maven组件风险层级','D4 · 面积为风险分'),tooltip:{formatter:p=>`${p.name}<br>风险分 ${p.value}`},series:[{type:'treemap',top:66,left:15,right:15,bottom:15,roam:false,nodeClick:false,breadcrumb:{show:false},label:{fontSize:11},itemStyle:{borderColor:'#fff',borderWidth:2,gapWidth:2},levels:[{color:[red,'#111','#555','#999','#c8c8cc']}],data:(a.mavenRisk||[]).map(x=>({name:x.package_name,value:Number(x.risk_score)}))}]})
  options.push({...base('漏洞风险散点','D5 · CVSS × 受影响版本'),grid:{left:58,right:30,top:75,bottom:48},xAxis:{name:'CVSS',min:0,max:10,splitLine:{lineStyle:{color:grid}}},yAxis:{name:'受影响版本',type:'value',splitLine:{lineStyle:{color:grid}}},series:[{type:'scatter',data:(a.riskScatter||[]).map(x=>[Number(x.cvss_score),Number(x.affected_version_count),Number(x.reference_count),x.vuln_id]),symbolSize:v=>Math.max(6,Math.min(28,6+Math.sqrt(v[2])*2)),itemStyle:{color:red,opacity:.58},tooltip:{formatter:p=>`${p.data[3]}<br>CVSS ${p.data[0]} · ${p.data[1]}个版本`}}]})
  const flow=a.riskFlow||[]; const visibleFlow=[...flow.filter(x=>Number(x.source_level)===1).slice(0,16),...flow.filter(x=>Number(x.source_level)===2).slice(0,16)]
  const links=visibleFlow.map(x=>({source:x.source_node,target:x.target_node,value:Number(x.flow_value)})); const nodes=[...new Set(links.flatMap(x=>[x.source,x.target]))].map(name=>({name}))
  options.push({...base('风险传播路径','D6 · 组织 → CWE → 严重度'),series:[{type:'sankey',top:70,left:18,right:18,bottom:18,data:nodes,links,lineStyle:{color:'gradient',curveness:.45,opacity:.28},itemStyle:{borderColor:'#111',borderWidth:1},label:{fontSize:10,color:black}}]})
  refs.forEach((holder,index)=>{const chart=echarts.init(holder.value);chart.setOption(options[index]);charts.push(chart)})
}
function resize(){charts.forEach(chart=>chart.resize())}
onMounted(()=>{load();window.addEventListener('resize',resize)});onBeforeUnmount(()=>{window.removeEventListener('resize',resize);charts.forEach(c=>c.dispose())})
</script>

<template>
  <div class="page dashboard">
    <div class="hero">
      <div><p class="eyebrow">JAVA OPEN-SOURCE RISK INTELLIGENCE</p><h1 class="page-title">六维漏洞<br><span>态势分析</span></h1></div>
      <div class="hero-copy"><p>HDFS保存公开漏洞数据，YARN调度Spark完成六维聚合，结果回写MySQL后由本页面读取。</p><span v-if="snapshot" class="status red">HDFS计算快照</span><span v-else class="status green">MySQL实时结果</span></div>
    </div>
    <div v-if="loading" class="empty">正在读取分析结果…</div>
    <div v-else-if="error" class="empty">{{error}}</div>
    <template v-else>
      <section class="metrics">
        <div><span>01</span><strong>{{total.toLocaleString()}}</strong><small>唯一漏洞</small></div>
        <div><span>02</span><strong>{{high.toLocaleString()}}</strong><small>高危及严重</small></div>
        <div><span>03</span><strong>{{packages}}</strong><small>高风险组件</small></div>
        <div><span>04</span><strong>{{cwes}}</strong><small>高频CWE</small></div>
      </section>
      <div class="section-head"><h2>SPARK ANALYTICS / 01—06</h2><span class="muted">六个维度 · 六种表达</span></div>
      <section class="charts"><article v-for="(_,index) in refs" :key="index" class="chart-panel"><div :ref="el=>refs[index].value=el" class="chart"></div><span class="folio">0{{index+1}}</span></article></section>
    </template>
  </div>
</template>

<style scoped>
.dashboard{max-width:1700px;margin:auto}.hero{display:grid;grid-template-columns:2fr 1fr;gap:40px;border-bottom:1px solid #111;padding-bottom:30px}.page-title span{color:var(--red)}.hero-copy{align-self:end;border-left:1px solid #111;padding-left:22px;max-width:440px;line-height:1.65}.hero-copy p{margin:0 0 18px}.metrics{display:grid;grid-template-columns:repeat(4,1fr);border-left:1px solid #111;border-top:1px solid #111;margin-top:30px}.metrics div{position:relative;min-height:150px;padding:24px;border-right:1px solid #111;border-bottom:1px solid #111;display:grid;align-content:end}.metrics span{position:absolute;top:14px;right:15px;color:var(--red);font-size:12px}.metrics strong{font-size:clamp(36px,5vw,74px);letter-spacing:-.06em;line-height:.85;font-variant-numeric:tabular-nums}.metrics small{margin-top:14px;color:#666}.charts{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));border-left:1px solid var(--line);border-top:1px solid var(--line)}.chart-panel{height:390px;position:relative;border-right:1px solid var(--line);border-bottom:1px solid var(--line)}.chart{width:100%;height:100%}.folio{position:absolute;right:12px;bottom:8px;color:var(--red);font-size:11px}@media(max-width:900px){.hero{grid-template-columns:1fr}.hero-copy{border-left:0;padding-left:0}.metrics{grid-template-columns:repeat(2,1fr)}.charts{grid-template-columns:1fr}}
</style>
