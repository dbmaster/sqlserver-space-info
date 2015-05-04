import java.util.ArrayList
import java.util.Iterator
import java.util.List
import java.util.Map.Entry

import com.branegy.dbmaster.connection.ConnectionProvider
import com.branegy.dbmaster.connection.Connector
import com.branegy.dbmaster.connection.Dialect
import com.branegy.dbmaster.core.Permission.Role
import com.branegy.dbmaster.core.Project
import com.branegy.dbmaster.model.DatabaseInfo
import com.branegy.inventory.model.Database
import com.branegy.service.connection.api.ConnectionService
import com.branegy.service.connection.model.DatabaseConnection

import java.sql.Statement
import java.sql.ResultSet

import com.branegy.dbmaster.connection.JdbcConnector

import java.sql.Connection

import com.jacob.activeX.ActiveXComponent
import com.jacob.com.ComThread
import com.jacob.com.Dispatch
import com.jacob.com.EnumVariant
import com.jacob.com.Variant

import com.branegy.dbmaster.connection.JDBCDialect
import java.text.DecimalFormat
import com.jacob.com.ComThread

class DbInfo {
    String name;
    String state;
    String recoveryMode;
    double dbSize;
}

class DriveInfo {
    String name;
    long size;
    long freeSpace;
}

class DbFile{
    String fileName;
    String location;
    double fileSize;
    double freeSize;
    int maxSize;
    String growth;
    String error;
}

DecimalFormat format = new DecimalFormat("###,###,###,###,###");
boolean sortByName = "Database Name".equalsIgnoreCase(p_sort_by);

ConnectionService connectionSrv = dbm.getService(ConnectionService.class);
if (p_servers!=null && p_servers.size()>0) {
    connections = p_servers.collect { serverName -> connectionSrv.findByName(serverName) }
} else {
    connections  = connectionSrv.getConnectionList()
}

for (DatabaseConnection connection : connections) {
    logger.info("Loading databases from ${connection.getName()}")
    
    Connector connector = null;
    try {
        connector = ConnectionProvider.getConnector(connection);
        Dialect dialect = connector.connect();
        
        if (!(dialect instanceof JDBCDialect) || !((JDBCDialect)dialect).getDialectName().contains("sqlserver")) {
            logger.info("Skipping checks for connection ${connection.getName()} as it is not a database one")
            continue;
        } else {
            logger.info("Connecting to ${connection.getName()}")
        }
        
        println "Server "+connection.getName()+"<br/>";
        
        Connection jdbcConnection = connector.getJdbcConnection(null)
        dbm.closeResourceOnExit(jdbcConnection)
        
        Statement statement = jdbcConnection.createStatement();
        ResultSet rs = statement.executeQuery("select SERVERPROPERTY('MachineName')");
        if (rs.next()){
            String machineName = rs.getString(1);
            println "Drives for machine ${machineName}<br/>";
            try{
                def driveInfo = [];
                long totalSize = 0;
                
                try{
                    ComThread.InitMTA();
                    ActiveXComponent objLocator = new ActiveXComponent("WbemScripting.SWbemLocator");
                    Variant service = objLocator.invoke("ConnectServer", new Variant(machineName), new Variant("root\\cimv2"));
                
                    ActiveXComponent axWMI = new ActiveXComponent(service.toDispatch());
                    Variant vCollection = axWMI.invoke("ExecQuery", new Variant("Select name, FreeSpace, Size, Status, StatusInfo from Win32_LogicalDisk where DriveType=3"));
                    
                    EnumVariant enumVariant = new EnumVariant(vCollection.toDispatch());
                    Dispatch item = null;
                    while (enumVariant.hasMoreElements()) {
                        item = enumVariant.nextElement().toDispatch();
                        String name = Dispatch.call(item, "Name").toString();
                        long size = Long.parseLong(Dispatch.call(item, "Size").toString());
                        long freeSpace = Long.parseLong(Dispatch.call(item, "FreeSpace").toString());
                        item.safeRelease();
                        
                        driveInfo.add(new DriveInfo(name:name,size:size,freeSpace:freeSpace));
                        totalSize += size;
                    }
                    enumVariant.safeRelease();
                    vCollection.safeRelease();
                    axWMI.safeRelease();
                    service.safeRelease();
                    objLocator.safeRelease();
                } finally {
                    ComThread.Release();
                }
                
                println "<table class=\"simple-table\" cellspacing=\"0\">";
                println "<tr style=\"background-color:#EEE\">";
                println "<th>Drive</th>";
                println "<th>Total Size (Mb)</th>";
                println "<th>Free Size (Mb)</th>";
                println "<th>Usage bar</th>";
                println "<th>% Free</th>";
                println "</tr>";
            
                driveInfo.each{ drive ->
                    println "<tr>";
                    println "<td>${drive.name}</td>";
                    println "<td style='text-align:right;'>"+format.format(drive.size/1048576.0)+"</td>";
                    println "<td style='text-align:right;'>"+format.format(drive.freeSpace/1048576.0)+"</td>";
                    println "<td style='width:400px;text-align:right;'>"+
                                "<div style='background-color:white;width:${Math.round(drive.size/totalSize*400)}px;border:1px solid black;'>"+
                                    "<div style='background-color:yellow;width:${Math.round((drive.size-drive.freeSpace)/totalSize*400)}px;'>" +
                                        "&nbsp;" +
                                    "</div>"+
                                "</div>"+
                            "</td>";
                    println "<td style='text-align:right;'>${format.format(100.0 * drive.freeSpace / drive.size)}</td>"
                    println "</tr>"
                }
                
                println "</table>";
                println "<br/>";
            } catch (Exception e){
                println "Cannot retrieve information on drives: ${e.getMessage()}<br/>";
            }
        } else {
            println "Cannot retrieve information on drives<br/>";
            continue;// not sqlserver
        }
        rs.close();
        statement.close();
                
        // read databases + state + recovery mode
        def dbInfoList = []
        statement = jdbcConnection.createStatement();
        rs = statement.executeQuery("select  db.name, state=db.state_desc, recovery_mode=recovery_model_desc from sys.databases db");
        while (rs.next()){
            dbInfoList.add(new DbInfo(name:rs.getString(1),state:rs.getString(2),recoveryMode:rs.getString(3)));
        }
        rs.close();
        statement.close();
        
        double totalDbSize = 0;
        dbFileListMap = [:];
        dbInfoList.each{i ->
            try{
                statement = jdbcConnection.createStatement();
                statement.executeUpdate("use [${i.name}]");
                statement.close();
                
                dbFileListMap[i.name] = [];
                statement = jdbcConnection.createStatement();
                rs = statement.executeQuery("""
                    select
                    name,
                    type_desc,
                    location=physical_name,
                    file_size =size/128.0,
                    space_used=CAST(FILEPROPERTY(name, 'SpaceUsed') AS INT)/128.0, 
                    -- free_space= file_size - space_used
                    max_size, -- -1 means unlimited
                    is_percent_growth, -- 1 means %, 0 means MegaBytes
                    growth
                    from sys.master_files db where db_name(database_id)='${i.name}';
                """);
                double dbSize = 0;
                while (rs.next()){
                    dbSize += rs.getDouble(4);
                    dbFileListMap[i.name].add(new DbFile(fileName:rs.getString(1),
                                              location:rs.getString(3),
                                              fileSize:rs.getDouble(4),
                                              freeSize:rs.getDouble(4)-rs.getDouble(5),
                                              maxSize:rs.getInt(6),
                                              growth:rs.getBoolean(7)?rs.getInt(8)+'%':format.format(rs.getInt(8)/128)
                                          ));
                }
                totalDbSize += dbSize;
                i.dbSize = dbSize;
                
                rs.close();
                statement.close();
            } catch (Exception e){
                dbFileListMap[i.name] = [new DbFile(error:e.getMessage())];
            }
        }
        
        // print table
        println "Databases<br/>";
        println "<table class=\"simple-table\" cellspacing=\"0\">";
        println "<tr style=\"background-color:#EEE\">";
        println "<th>Database Name</th>";
        println "<th>DB State</th>";
        println "<th>Recovery Mode</th>";
        println "<th>File Name</th>";
        println "<th>Location</th>";
        println "<th>Total Size (Mb)</th>";
        println "<th>Free Size (Mb)</th>";
        println "<th>Max Size (Mb)</th>";
        println "<th>Growth (% or Mb)</th>";
        println "<th>% Total Size of Server</th>";
        println "<th>% Free Size</th>";
        println "</tr>";
        dbInfoList
        .sort{a,b -> (sortByName
            ? a.name.compareToIgnoreCase(b.name)
            : a.dbSize == b.dbSize ? a.name.compareToIgnoreCase(b.name) : a.dbSize-b.dbSize)}
        .each{i ->
            item = dbFileListMap[i.name];
            item.eachWithIndex { j, index ->
                 println "<tr>";
                 if (index == 0){
                     println "<td rowspan='${item.size()}'>${i.name}</td>";
                     println "<td rowspan='${item.size()}'>${i.state}</td>";
                     println "<td rowspan='${item.size()}'>${i.recoveryMode}</td>";
                 }
                 if (j.error !=null){
                     println "<td colspan='8'>${j.error}</td>";
                 } else {
                     println "<td>${j.fileName}</td>";
                     println "<td>${j.location}</td>";
                     println "<td style='text-align:right;'>${format.format(j.fileSize)}</td>";
                     println "<td style='text-align:right;'>${format.format(j.freeSize)}</td>";
                     println "<td style='text-align:right;'>${j.maxSize==-1?'unlimited':format.format(j.maxSize)}</td>";
                     println "<td style='text-align:right;'>${j.growth}</td>";
                     println "<td style='text-align:right;'>${format.format(100.0*i.dbSize/totalDbSize)}%</td>";
                     println "<td style='text-align:right;'>${format.format(100.0*j.freeSize/j.fileSize)}%</td>";
                 }
                 println "</tr>";
            }
        }
        
        println "</table>";
        println "<br/>";
        
    } catch (Exception e) {
        logger.info("Cannot load database ${e.getMessage()}\n")
        println "Cannot load database ${e.getMessage()}\n"
    } finally {
        if (connector!=null) {
            connector.closeConnection();
        }
    }
}