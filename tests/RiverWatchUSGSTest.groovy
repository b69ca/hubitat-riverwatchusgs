// Run from the repository root with Groovy 2.4.21.
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

def state = [:]
def settings = [siteNumber:'01646500', watchStage:5d, warningStage:7d, criticalStage:9d, rapidRisePerHour:0.5d]
def readings = [:]
def events = []
def requests = []
def jobs = [:]
def warnings = []
long clock = 1790092800000L
def location = [timeZone:TimeZone.getTimeZone('America/Denver')]

def binding = new Binding([
  state:state, settings:settings, location:location,
  metadata:{ Closure ignored -> }, now:{ -> clock },
  device:[currentValue:{ String name -> readings[name] }, updateSetting:{ String name, Map value -> settings[name] = value.value }],
  log:[warn:{ message -> warnings << message.toString() }, debug:{ ignored -> }, info:{ ignored -> }],
  sendEvent:{ Map event -> events << event; readings[event.name] = event.value },
  runIn:{ seconds, handler, Map options = [:] -> jobs[handler] = [seconds:seconds, options:options] },
  unschedule:{ String handler = null -> if (handler) jobs.remove(handler) else jobs.clear() },
  runEvery1Hour:{ handler -> jobs[handler] = [every:3600] },
  schedule:{ expression, handler -> jobs[handler] = [cron:expression] },
  asynchttpGet:{ callback, params, data -> requests << [callback:callback, params:params, data:data] }
])

def driver = new GroovyShell(binding).parse(new File('RiverWatchUSGS.groovy'))
driver.run()

def ok = { body -> [hasError:{ -> false }, status:200, getJson:{ -> body }] }
def failed = { int status -> [hasError:{ -> true }, status:status, getJson:{ -> null }] }
def iso = { long time -> new Date(time).format("yyyy-MM-dd'T'HH:mm:ssXXX", TimeZone.getTimeZone('UTC')) }
def metadataBody = { String number = '01646500' -> [
  type:'FeatureCollection', features:[[type:'Feature', id:"USGS-${number}", properties:[
    monitoring_location_name:'POTOMAC RIVER TEST STATION', site_type:'Stream', state_name:'Maryland', county_name:'Montgomery County'
  ], geometry:[type:'Point', coordinates:[-77.1d, 38.9d]]]]
] }
def valuesBody = { Double stage, Double flow, long time = clock ->
  List features = []
  if (flow != null) features << [type:'Feature', properties:[parameter_code:'00060', time:iso(time), value:"${flow}", unit_of_measure:'ft^3/s', approval_status:'Provisional']]
  if (stage != null) features << [type:'Feature', properties:[parameter_code:'00065', time:iso(time), value:"${stage}", unit_of_measure:'ft', approval_status:'Provisional']]
  [type:'FeatureCollection', features:features]
}

driver.installed()
assert requests.empty
assert readings.watchStatus == 'setup'
assert readings.numberOfButtons == 3
driver.initialize()
assert jobs.refresh.every == 3600
assert requests[-1].callback == 'metadataResponse'
assert requests[-1].params.query.id == 'USGS-01646500'
assert !requests[-1].params.toString().toLowerCase().contains('apikey')
def metaRequest = requests[-1]
driver.metadataResponse(ok(metadataBody()), metaRequest.data)
assert requests[-1].callback == 'valuesResponse'
assert requests[-1].params.query.parameter_code == '00060,00065'
def firstValues = requests[-1]
driver.valuesResponse(ok(valuesBody(4d, 2000d)), firstValues.data)
assert readings.watchStatus == 'ready'
assert readings.riverState == 'normal'
assert readings.stationNumber == '01646500'
assert readings.stationName == 'POTOMAC RIVER TEST STATION'
assert readings.gageHeight.toString() == '4.00'
assert readings.discharge.toString() == '2000.0'
assert readings.gageTrend == 'unknown'
assert readings.stationUrl.endsWith('/USGS-01646500/')
assert !events.any { it.name == 'pushed' }
assert state.hasBaseline && state.samples.size() == 1
println 'PASS: setup gate, station metadata, no-key query and baseline attributes'

clock += 3600000L
driver.refresh()
assert requests[-1].callback == 'valuesResponse' // metadata is cached
driver.valuesResponse(ok(valuesBody(5.2d, 2400d)), requests[-1].data)
assert readings.riverState == 'watch'
assert readings.gageTrend == 'rising'
assert readings.gageChange.toString() == '1.20'
assert readings.gageChangePerHour.toString() == '1.20'
assert events.findAll { it.name == 'pushed' }[-1].value == 3 // rapid rise outranks watch
assert readings.notificationType == 'rapidRise'

clock += 3600000L
driver.refresh()
driver.valuesResponse(ok(valuesBody(7.1d, 3200d)), requests[-1].data)
assert readings.riverState == 'warning'
assert events.findAll { it.name == 'pushed' }[-1].value == 2
assert readings.notificationType == 'warning'
assert readings.conditionSummary.contains('warning')
println 'PASS: trend calculations, rapid-rise detection and warning precedence'

clock += 3600000L
driver.refresh()
driver.valuesResponse(ok(valuesBody(9.2d, 5000d)), requests[-1].data)
assert readings.riverState == 'critical'
assert readings.notificationType == 'critical'
assert events.findAll { it.name == 'pushed' }[-1].value == 2

clock += 3600000L
driver.refresh()
driver.valuesResponse(ok(valuesBody(4.5d, 2100d)), requests[-1].data)
assert readings.riverState == 'normal'
assert readings.notificationType == 'recovery'
assert events.findAll { it.name == 'pushed' }[-1].value == 1
assert readings.gageTrend == 'falling'
println 'PASS: critical escalation, falling trend and recovery event'

clock += 14400000L
driver.refresh()
driver.valuesResponse(ok(valuesBody(4.5d, 2100d, clock - 14400000L)), requests[-1].data)
assert readings.riverState == 'stale'
assert readings.dataAgeMinutes == 240L
assert readings.notificationType == 'stale'
assert events.findAll { it.name == 'pushed' }[-1].value == 3

driver.testWatchEvent()
assert events.findAll { it.name == 'pushed' }[-1].value == 1
driver.testWarningEvent()
assert events.findAll { it.name == 'pushed' }[-1].value == 2
driver.testChangeEvent()
assert events.findAll { it.name == 'pushed' }[-1].value == 3
assert readings.notificationText.startsWith('TEST:')
println 'PASS: stale-data and test automation events'

settings.siteNumber = '06730200'
clock += 3600000L
driver.initialize()
assert requests[-1].callback == 'metadataResponse'
assert !state.hasBaseline && state.samples == []
def changedMeta = requests[-1]
driver.metadataResponse(failed(503), changedMeta.data)
assert requests[-1].callback == 'valuesResponse'
driver.valuesResponse(ok(valuesBody(null, 125d)), requests[-1].data)
assert readings.watchStatus == 'partial'
assert readings.lastError.contains('HTTP 503')
assert readings.gageHeight == 0
assert readings.discharge.toString() == '125.0'

int beforeStale = requests.size()
driver.valuesResponse(ok(valuesBody(5d, 200d)), changedMeta.data)
assert requests.size() == beforeStale

boolean invalid = false
settings.siteNumber = 'bad-site'
clock += 3600000L
driver.initialize()
assert readings.watchStatus == 'error'
assert readings.lastError.contains('8 to 15 digits')

def restored = new JsonSlurper().parseText(JsonOutput.toJson(state))
assert restored.samples instanceof List
assert JsonOutput.toJson(state).length() < 20000
println 'PASS: station changes, partial metadata failure, validation, stale callbacks and JSON-safe state'
println 'ALL CHECKS PASSED'
