/**
 *  FortrezZ Flow Meter Interface
 *
 *  Copyright 2016 FortrezZ, LLC
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 */
metadata {
	definition (name: "FortrezZ Flow Meter Interface", namespace: "tsuthar", author: "Tarak Suthar") {
		capability "Battery"
		capability "Energy Meter"
		capability "Temperature Measurement"
        capability "Sensor"
        capability "Water Sensor"

/*		Future implementation for charts

		// Need to find out how this works
		capability "Image Capture" 
        attribute "chartMode", "string"
        command "chartMode"

		// May be unnecessary, we can use RE4.0
        command "setHighFlowLevel", ["number"]

  */
        command "resetGpmHigh" //zero out the high gpm
        command "resetAlarmState" //reset the alarm state
        command "zero" //zero out meter on the device
        command "resetCurrentFlowValues"

        attribute "gpm", "number" //Represent the current calculated gpm
        attribute "cumulative", "number" //latest meter value reported by the device
        
        
        attribute "alarmState", "string" //Use for alarms only
        attribute "waterState", "string" //Use for water flow state only
        

		attribute "gpmHigh", "number" //shows the high gpm with timestamp
        attribute "gpmHighValue", "number" //Represent the highest gpm recorded
        attribute "lastReset", "string"

		//calculate current flow rate and will assist in raising notifications for thresholds
		attribute "currentFlowRate", "number"
		attribute "currentFlowHigh", "number"
		attribute "currentFlowStartTime", "date"
		attribute "currentFlowAverage", "number"
		attribute "currentFlowMeterStart", "number"
		attribute "currentFlowDuration","number"


        // attribute "lastThreshhold", "number" //removing for now

	    fingerprint deviceId: "0x2101", inClusters: "0x5E, 0x86, 0x72, 0x5A, 0x73, 0x71, 0x85, 0x59, 0x32, 0x31, 0x70, 0x80, 0x7A"
	}

	/*
	simulator {
		// TODO: define status and reply messages here
	}
	*/
    
    preferences {
       
       /* Neither of these are necessary at this time
       
       input "gallonThreshhold", "decimal", title: "High Flow Rate Threshhold", description: "Flow rate (in gpm) that will trigger a notification.", defaultValue: 5, required: false, displayDuringSetup: true
       input ("version", "text", title: "Plugin Version 1.5", description:"", required: false, displayDuringSetup: true)
       
       */
       
       //Some issue w/ this original line
		//input("registerEmail", type: "email", required: false, title: "Email Address", description: "Register your device with FortrezZ", displayDuringSetup: true)
		
		//Nothing changes this value on the device yet.  Set to 1
       input "reportThreshhold", "decimal", title: "Reporting Rate Threshhold", description: "The time interval between meter reports while water is flowing. 6 = 60 seconds, 1 = 10 seconds. Options are 1, 2, 3, 4, 5, or 6.", defaultValue: 1, required: false, displayDuringSetup: true

    }


    
}

// parse events into attributes
def parse(String description) {
	def results = []
	if (description.startsWith("Err")) {
	    results << createEvent(descriptionText:description, displayed:true)
	} else {
		def cmd = zwave.parse(description, [ 0x80: 1, 0x84: 1, 0x71: 2, 0x72: 1 ])
		if (cmd) {
			results << createEvent( zwaveEvent(cmd) )
		}
	}
    
	log.debug "zwave parsed to ${results.inspect()}"
	return results
}

def updated()
{
	log.debug("Updated")
}

def configure() {
	log.debug "Configuring FortrezZ flow meter interface (FMI)..."
	log.debug "Setting reporting interval to ${reportThreshhold}"

    def cmds = delayBetween([
		zwave.configurationV2.configurationSet(configurationValue: [(int)Math.round(reportThreshhold)], parameterNumber: 4, size: 1).format(),
    	zwave.configurationV2.configurationSet(configurationValue: [(int)Math.round(gallonThreshhold*10)], parameterNumber: 5, size: 1).format()
    ],200)
    log.debug "Configuration report for FortrezZ flow meter interface (FMI): '${cmds}'"
    cmds
}

def zero()
{
	delayBetween([
		zwave.meterV3.meterReset().format(),
        zwave.meterV3.meterGet().format(),
        zwave.firmwareUpdateMdV2.firmwareMdGet().format(),
    ], 100)
    sendEvent(name: "cumulative", value: 0, displayed: false)

}

def resetAlarmState()
{
	 sendAlarm("Normal")
	 
	 //used for testing time
	//update average
	def localTime = now() //new Date()     
	def timeElapsed = localTime - device.currentState('currentFlowStartTime').date.getTime()
    def localNow = now()
	
	//def averageFlow
	if (state.debug) log.debug "Local time: ${localTime}.  CurrentFlowStartTime: ${device.currentState('currentFlowStartTime').date}.  Time elapsed is ${timeElapsed}"
    if (state.debug) log.debug "now is ${localNow}"
	 
	 
}

def resetGpmHigh()
{
	sendEvent(name: "gpmHigh", value: "None" , displayed: true)
    sendEvent(name: "gpmHighValue", value: 0, displayed: false)
	
}

def resetCurrentFlowValues()
{
	sendEvent(name: "currentFlowRate", value: 0, displayed: true)
    sendEvent(name: "currentFlowHigh", value: 0, displayed: false)
	sendEvent(name: "currentFlowStartTime", value: "None" , displayed: true)
    sendEvent(name: "currentFlowAverage", value: 0, displayed: false)
	sendEvent(name: "currentFlowMeterStart", value: 0 , displayed: true)
    sendEvent(name: "currentFlowDuration", value: 0, displayed: false)


}

def zwaveEvent(hubitat.zwave.commands.sensormultilevelv5.SensorMultilevelReport cmd)
{
	log.debug cmd
	def map = [:]
	if(cmd.sensorType == 1) {
		map = [name: "temperature"]
        if(cmd.scale == 0) {
        	map.value = getTemperature(cmd.scaledSensorValue)
        } else {
	        map.value = cmd.scaledSensorValue
        }
        map.unit = location.temperatureScale
	} 
	return map
}

def zwaveEvent(hubitat.zwave.commands.meterv3.MeterReport cmd)
{
	
	
	if (state.debug) log.debug "scaledMeterValue is ${cmd.scaledMeterValue}"
    if (state.debug) log.debug "scaledPreviousMeterValue is ${cmd.scaledPreviousMeterValue}"
	
    def timeString = new Date().format("MM-dd-yy h:mm a", location.timeZone)
    def delta = Math.round((((cmd.scaledMeterValue - cmd.scaledPreviousMeterValue) / (reportThreshhold*10)) * 60)*100)/100 //rounds to 2 decimal positions

    if (delta < 0) { //There should never be any negative values
			if (state.debug) log.debug "We just detected a negative delta value that won't be processed: ${delta}"
            sendEvent(name: "errorHist", value: "Negative value detected on "+timeString, descriptionText: text, displayed: true)
            return
    } else if (delta > 60) { //There should never be any crazy high gallons as a delta, even at 1 minute reporting intervals.  It's not possible unless you're a firetruck.
    		if (state.debug) log.debug "We just detected a crazy high delta value that won't be processed: ${delta}"
            sendEvent(name: "errorHist", value: "Crazy high delta value detected on "+timeString, descriptionText: text, displayed: true)
            return
    } else if (delta == 0) {
    		if (state.debug) log.debug "Flow has stopped, so process what the meter collected."
            if (cmd.scaledMeterValue == device.currentState('cumulative').doubleValue) {
            	if (state.debug) log.debug "Current and previous flow values were the same, so skip processing."
                sendEvent(name: "errorHist", value: "Current and previous flow values were the same at "+timeString, descriptionText: text, displayed: true)
                return
            }
            if (cmd.scaledMeterValue < device.currentState('cumulative').doubleValue) {
            	if (state.debug) log.debug "Current flow value is less than the previous flow value and that should never happen, so skip processing."
                sendEvent(name: "errorHist", value: "Current flow value was less than the previous flow value at "+timeString, descriptionText: text, displayed: true)
                return
            }
			/*def newgpmTotal = device.currentState('gpmTotal').doubleValue
			def prevCumulative = cmd.scaledMeterValue - newgpmTotal
            sendDataToCloud(prevCumulative)
			if (prevCumulative > device.currentState('gallonHighValue')?.doubleValue) {
                sendEvent(name: "gallonHigh", value: String.format("%3.1f",prevCumulative)+" gallons on"+"\n"+timeString as String, displayed: true)
                sendEvent(name: "gallonHighValue", value: String.format("%3.1f",prevCumulative), displayed: false)
            }
            sendEvent(name: "gpmTotal", value: cmd.scaledMeterValue, displayed: false)
            sendEvent(name: "gpmLastUsed", value: String.format("%3.1f",prevCumulative), displayed: true)
            */
            sendEvent(name: "waterState", value: "none", displayed: true)
            sendEvent(name: "gpm", value: delta, displayed: false)
            sendEvent(name: "cumulative", value: cmd.scaledMeterValue, displayed: false)
            
            //Flow rate reset
            sendEvent(name: "currentFlowRate", value: 0, displayed: false)
            sendEvent(name: "currentFlowHigh", value: 0, displayed: false)
            sendEvent(name: "currentFlowAverage", value: 0, displayed: false)
            sendEvent(name: "currentFlowStartTime", value: "", displayed: false)
            sendEvent(name: "currentFlowMeterStart", value: 0, displayed: false)
            sendEvent(name: "currentFlowDuration", value: 0, displayed: false)
            
            
            //To be removed later
            //sendEvent(name: "alarmState", value: "Normal Operation", descriptionText: text, displayed: true)

			return
    } else {
        	sendEvent(name: "gpm", value: delta, displayed: false)
            if (state.debug) log.debug "flowing at ${delta}"
            if (delta > device.currentState('gpmHighValue')?.doubleValue) {
                sendEvent(name: "gpmHigh", value: String.format("%3.1f",delta)+" gpm on"+"\n"+timeString as String, displayed: true)
                sendEvent(name: "gpmHighValue", value: String.format("%3.1f",delta), displayed: false)
            }
            
            if (state.debug) log.debug "cmd.scaledPreviousMeterValue is ${cmd.scaledPreviousMeterValue} and device cumulative is ${device.currentState('cumulative').doubleValue}"
            //See if this is the start of a flow.   
            if (cmd.scaledPreviousMeterValue == device.currentState('cumulative').doubleValue) {
           		def localTime = new Date(now()).format("HH:mm:ss", location.timeZone)             

          		if (state.debug) log.debug "This is a new flow.  Setting currentFlowMeterStart to ${cmd.scaledPreviousMeterValue} and localTime to ${localTime}"
          		
          		sendEvent(name: "currentFlowMeterStart", value: cmd.scaledPreviousMeterValue, displayed: false)   
           		sendEvent(name: "currentFlowStartTime", value: localTime, displayed: false)
            } else { //existing flow
            
            	if (state.debug) log.debug "This is an existing flow"
            	

                //update average and duration
                def localTime = now()     
                def timeElapsedMs = localTime - device.currentState('currentFlowStartTime').date.getTime()
                def timeElapsedMin = (timeElapsedMs/1000)/60
                def totalWaterUsed = cmd.scaledMeterValue - device.currentState('currentFlowMeterStart').doubleValue

                def averageFlow = Math.round( (totalWaterUsed/timeElapsedMin) * 100)/100
                if (state.debug) log.debug "TimeElapsedMin: ${timeElapsedMin} | TotalWaterUsed: ${totalWaterUsed} | AverageFlow: ${averageFlow} | "

                //update timeElapsedMin to only 2 decimal places and then send 
                timeElapsedMin = Math.round( timeElapsedMin * 100 )/100
                sendEvent(name: "currentFlowAverage", value: averageFlow, displayed: false)
                sendEvent(name: "currentFlowDuration", value: timeElapsedMin, displayed: false)
            
            }
            //update flow rate
            sendEvent(name: "currentFlowRate", value: delta, displayed: false)
            
            //update the current flow's highest rate if it exceeds
            if (delta > device.currentState('currentFlowHigh').doubleValue )
            	sendEvent(name: "currentFlowHigh", value: delta, displayed: false)
	           
      		sendEvent(name: "waterState", value: "flow")
            //sendEvent(name: "alarmState", value: "Water is flowing", descriptionText: text, displayed: true)
			
            return
    }
	return
	
	
	
}

def zwaveEvent(hubitat.zwave.commands.alarmv2.AlarmReport cmd)
{
	def map = [:]
    if (cmd.zwaveAlarmType == 8) // Power Alarm
    {
    	map.name = "powerState" // For Tile (shows in "Recently")
        if (cmd.zwaveAlarmEvent == 2) // AC Mains Disconnected
        {
            map.value = "disconnected"
            sendAlarm("acMainsDisconnected")
        }
        else if (cmd.zwaveAlarmEvent == 3) // AC Mains Reconnected
        {
            map.value = "reconnected"
            sendAlarm("acMainsReconnected")
        }
        else if (cmd.zwaveAlarmEvent == 0x0B) // Replace Battery Now
        {
            map.value = "noBattery"
            sendAlarm("replaceBatteryNow")
        }
        else if (cmd.zwaveAlarmEvent == 0x00) // Battery Replaced
        {
            map.value = "batteryReplaced"
            sendAlarm("batteryReplaced")
        }
    }
    else if (cmd.zwaveAlarmType == 4) // Heat Alarm
    {
    	map.name = "heatState"
        if (cmd.zwaveAlarmEvent == 0) // Normal
        {
            map.value = "normal"
        }
        else if (cmd.zwaveAlarmEvent == 1) // Overheat
        {
            map.value = "overheated"
            sendAlarm("tempOverheated")
        }
        else if (cmd.zwaveAlarmEvent == 5) // Underheat
        {
            map.value = "freezing"
            sendAlarm("tempFreezing")
        }
    }
    else if (cmd.zwaveAlarmType == 5) // Water Alarm
    {
    	map.name = "waterState"
        if (cmd.zwaveAlarmEvent == 0) // Normal
        {
            map.value = "none"
            sendEvent(name: "water", value: "dry")
        }
        else if (cmd.zwaveAlarmEvent == 6) // Flow Detected
        {
        	if(cmd.eventParameter[0] == 2)
            {
                map.value = "flow"
                sendEvent(name: "water", value: "dry")
            }
            else if(cmd.eventParameter[0] == 3)
            {
            	map.value = "overflow"
                sendAlarm("waterOverflow")
                sendEvent(name: "water", value: "wet")
            }
        }
    }
    //log.debug "alarmV2: $cmd"
    
	return map
}

def zwaveEvent(hubitat.zwave.commands.batteryv1.BatteryReport cmd) {
	def map = [:]
	if(cmd.batteryLevel == 0xFF) {
		map.name = "battery"
		map.value = 1
		map.descriptionText = "${device.displayName} has a low battery"
		map.displayed = true
	} else {
		map.name = "battery"
		map.value = cmd.batteryLevel > 0 ? cmd.batteryLevel.toString() : 1
		map.unit = "%"
		map.displayed = false
	}
	return map
}

def zwaveEvent(hubitat.zwave.Command cmd)
{
	log.debug "COMMAND CLASS: $cmd"
}




def getTemperature(value) {
	if(location.temperatureScale == "C"){
		return value
    } else {
        return Math.round(celsiusToFahrenheit(value))
    }
}



def sendAlarm(text)
{
	sendEvent(name: "alarmState", value: text, descriptionText: text, displayed: false)
}

/*  Let HE figure this out
 *
 *
 *
 *
def setThreshhold(rate)
{
	log.debug "Setting Threshhold to ${rate}"
    
    def event = createEvent(name: "lastThreshhold", value: rate, displayed: false)
    def cmds = []
    cmds << zwave.configurationV2.configurationSet(configurationValue: [(int)Math.round(rate*10)], parameterNumber: 5, size: 1).format()
    sendEvent(event)
    return response(cmds) // return a list containing the event and the result of response()
}
*/


/*
 * Commenting out chart functions for now
 
def take() {
	def mode = device.currentValue("chartMode")
    if(mode == "day")
    {
    	take1()
    }
    else if(mode == "week")
    {
    	take7()
    }
    else if(mode == "month")
    {
    	take28()
    }
}

def chartMode(string) {
	log.debug("ChartMode")
	def state = device.currentValue("chartMode")
    def tempValue = ""
	switch(state)
    {
    	case "day":
        	tempValue = "week"
            break
        
        case "week":
        	tempValue = "month"
            break
            
        case "month":
        	tempValue = "day"
            break
            
        default:
        	tempValue = "day"
            break
    }
	sendEvent(name: "chartMode", value: tempValue)
    take()
}

def take1() {
    api("24hrs", "") {
        log.debug("Image captured")

        if(it.headers.'Content-Type'.contains("image/png")) {
            if(it.data) {
                storeImage(getPictureName("24hrs"), it.data)
            }
        }
    }
}

def take7() {
    api("7days", "") {
        log.debug("Image captured")

        if(it.headers.'Content-Type'.contains("image/png")) {
            if(it.data) {
                storeImage(getPictureName("7days"), it.data)
            }
        }
    }
}

def take28() {
    api("4weeks", "") {
        log.debug("Image captured")

        if(it.headers.'Content-Type'.contains("image/png")) {
            if(it.data) {
                storeImage(getPictureName("4weeks"), it.data)
            }
        }
    }
}

def sendDataToCloud(double data)
{
    def params = [
        uri: "https://iot.swiftlet.technology",
        path: "/fortrezz/post.php",
        body: [
            id: device.id,
            value: data,
            email: registerEmail
        ]
    ]

	//log.debug("POST parameters: ${params}")
    try {
        httpPostJson(params) { resp ->
            resp.headers.each {
                //log.debug "${it.name} : ${it.value}"
            }
            log.debug "sendDataToCloud query response: ${resp.data}"
        }
    } catch (e) {
        log.debug "something went wrong: $e"
    }
}

private getPictureName(category) {
  //def pictureUuid = device.id.toString().replaceAll('-', '')
  def pictureUuid = java.util.UUID.randomUUID().toString().replaceAll('-', '')

  def name = "image" + "_$pictureUuid" + "_" + category + ".png"
  return name
}

def api(method, args = [], success = {}) {
  def methods = [
    //"snapshot":        [uri: "http://${ip}:${port}/snapshot.cgi${login()}&${args}",        type: "post"],
    "24hrs":      [uri: "https://iot.swiftlet.technology/fortrezz/chart.php?uuid=${device.id}&tz=${location.timeZone.ID}&type=1", type: "get"],
    "7days":      [uri: "https://iot.swiftlet.technology/fortrezz/chart.php?uuid=${device.id}&tz=${location.timeZone.ID}&type=2", type: "get"],
    "4weeks":     [uri: "https://iot.swiftlet.technology/fortrezz/chart.php?uuid=${device.id}&tz=${location.timeZone.ID}&type=3", type: "get"],
  ]

  def request = methods.getAt(method)

  return doRequest(request.uri, request.type, success)
}

private doRequest(uri, type, success) {
  log.debug(uri)

  if(type == "post") {
    httpPost(uri , "", success)
  }

  else if(type == "get") {
    httpGet(uri, success)
  }
}

* End commenting for chart functions
*/
