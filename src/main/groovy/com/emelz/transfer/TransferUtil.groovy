package com.emelz.transfer

import java.sql.*
import groovy.sql.Sql

/**
 * Singleton class used for Neatworks-to-Quicken transfer.
 */
class TransferUtil {
    def quickenDataDir
    def neatDataDir
    def nids = new HashSet()
    def labelToFieldId = 
        [ 'Vendor' : 66,
          'Date' : 4,
          'Amount' : 25,
          'Payment Type' : 17,
          'Category' : 30,
          'Sales Tax' : 16,
          'Personal' : 32,
          'Notes' : 64 ]
    def labelToType = 
        [ 'Vendor' : 'String', 
          'Date' : 'Timestamp',
          'Amount' : 'Decimal',
          'Payment Type' : 'String',
          'Category' : 'String',
          'Sales Tax' : 'Decimal',
          'Personal' : 'Boolean',
          'Notes' : 'String', 
         ]
    def ant = new AntBuilder()
    def dbs = [:]
    def payees = [:]
    def stalePayees = true
    def categories = [:]
    def staleCategories = true
    def cnxns = [:]


    // Quicken Stuff
    def genGuid() {
        def lengths = [ 8, 4, 4, 4, 12 ]
        def chunks = [] 
        lengths.each {
            def choices = [ 0,1,2,3,4,5,6,7,8,9,'A','B','C','D','E','F']
            def chunk = ''
            for (i in 0..it) {
                Collections.shuffle(choices, new Random())
                chunk += choices[0]
            }
            chunks << chunk
        }
        chunks.join('-')
    }


    def execUpdate(id, stmt) {
        dbs[id].execute(stmt)
    }


    def bumpPk(tableName) {
        def rows = execQuery('quicken', "select max(z_max) from z_primarykey where z_name='$tableName'", ["Int"])
        def max = rows[0][0]
        max++
        execUpdate('quicken', "update z_primarykey set z_max=$max where z_name='" + tableName + "'")
    }


    def bumpPayee(quickenId) {
        def rows = execQuery('quicken', "select z_opt from zuserpayee where zquickenid=$quickenId", ["Int"])
        if (rows == null) return
        if (rows[0] == null) {
           println "problem in bumpPayee for id $quickenId"
           return
        }
        def max = rows[0][0]
        max++
        execUpdate('quicken', "update zuserpayee set z_opt=$max where zquickenid=$quickenId")
    }


    def bumpCategory(quickenId) {
        def rows = execQuery('quicken', "select z_opt from ztag where zquickenid=$quickenId", ["Int"])
        if (rows == null) return
        if (rows[0] == null) {
           println "problem in bumpCategory for id $quickenId"
           return
        }
        def max = rows[0][0]
        max++
        execUpdate('quicken', "update ztag set z_opt=$max where zquickenid=$quickenId")
    }


    // TODO: this can be optimized by assume gatherPayees has been called beforehand
    // and updating payees table directly after the insert.
    def maybeAddPayee(payeeName) {
        if (stalePayees) gatherPayees()
        def maxPayee = 0
        def payeeExists = false
        payees.each { k,v -> if (k > maxPayee) maxPayee = k }
        payees.each { k,v -> if (v == payeeName) payeeExists = true }
        if (payeeExists) return 
        maxPayee++
        def stmt = "insert into zuserpayee ( Z_PK, Z_ENT, Z_OPT, ZQUICKENID, ZDELETIONCOUNT, ZHIDDENINLISTS, ZRETIRED, ZCREATIONDATE, ZMODIFICATIONDATE, ZIMPORTSOURCEFILEID, ZDATASOURCE, ZURL, ZADDRESS, ZNAME, ZSYNCID ) values ( $maxPayee, 48, 0, $maxPayee, 0, null, 0, '317484794.171017', '317484794.171017', null, null, null, null, '" + payeeName + "', null)"
        execUpdate('quicken', stmt)
        bumpPk('UserPayee')
        gatherPayees()
    }


    def gatherPayees() {
        payees = [:]
        def itemTuples = execQuery('quicken', "select z_pk, zname from zuserpayee", ["Int", "String"])
        itemTuples.each {
           def id, name
           ( id, name ) = it
           payees[id] = name
        }
        stalePayees = false
    }

    
    def maybeAddCategory(categoryName) {
        if (staleCategories) gatherCategories()
        staleCategories = false
        def maxCategory = 0
        def categoryExists = false
        categories.each { k,v -> if (k > maxCategory) maxCategory = k }
        categories.each { k,v -> if (v == categoryName) categoryExists = true }
        if (categoryExists) { return }
        maxCategory++
        def rows = execQuery('quicken', "select max(z_pk) from ztag", ["Int"])
        def maxPk = rows[0][0]
        maxPk++
        def stmt = "insert into ZTAG ( Z_PK, Z_ENT, Z_OPT, ZQUICKENID, ZDELETIONCOUNT, ZTYPE, ZTAXRELATED, ZRETIRED, ZTAXREF, ZTAXCOPYNUM, ZFAVORITE, ZPARENTCATEGORY, ZTAGBUNDLE, ZPUBLISHEDTOACE, ZMODIFICATIONDATE, ZCREATIONDATE, ZFLEXIBILITY, ZSYNCID, ZNAME, ZDATASOURCE, ZUSERDESCRIPTION, ZIMPORTSOURCEID, ZIMPORTSOURCECLASS, ZTAXREFDESCRIPTION, ZIMPORTSOURCEFILEID, ZLOCALVALUE, ZPICTURE ) values ( $maxPk, 37, 1, $maxCategory, 0, null, 0, 0, null, null, 0, null, null, null, '317500162.486785', '317500162.486785', null, null, $categoryName, null, null, null, null, null, null, null, null)"
        execUpdate('quicken', stmt)
        bumpPk('CategoryTag')
        gatherCategories()
    }


    def gatherCategories() {
        categories = [:]
        def itemTuples = execQuery('quicken', "select zquickenid, zname from ztag where z_ent=37", ["Int", "String"])
        itemTuples.each {
           def id, name
           ( id, name ) = it
           categories[id] = name
        }
        staleCategories = false
    }


    def addAttachment(srcPath, transactionId) {
        def rows = execQuery('quicken', "select max(z_pk) from zattachment", ["Int"])
        def pk = rows[0][0] + 1
        def guid = genGuid()
        def fileName = srcPath.substring(srcPath.lastIndexOf('/') + 1)
        // TODO: unhardcode this
        def destDir = "$quickenDataDir/attachments/$transactionId/$guid"
        new File(destDir).mkdirs()
        def destPath = "$destDir/$fileName"
        ant.copy(file:srcPath, todir:destDir)
    
        def stmt = "insert into zattachment ( Z_PK, Z_ENT, Z_OPT, ZDELETIONCOUNT, ZQUICKENID, ZTRANSACTION, Z44_TRANSACTION, ZMODIFICATIONDATE, ZCREATIONDATE, ZORIGINALFILEPATH, ZGUID, ZSTOREDFILEPATH, ZSYNCID, ZDATASOURCE, ZOTHERINFORMATION ) values ( $pk, 4, 1, 0, $pk, $transactionId, 44, 317510330.134597, 317510330.134597, $srcPath, $guid, $destPath, null, null, null)"
       execUpdate('quicken', stmt)
    }


    def addCashTransaction(date, payee, category, amount, pdf, note, accountName, accountType) {
        if (payee == null) payee = ''
        if (category == null) category = ''
        maybeAddPayee(payee)
        maybeAddCategory(category)
        maybeAddAccount(accountName, accountType)
        def payeeId = -1
        payees.each { k,v -> if (v == payee) payeeId = k }
        def categoryId = -1
        categories.each { k,v -> if (v == category) categoryId = k }
        bumpPayee(payeeId)
        bumpCategory(categoryId)
    
        def rows = execQuery('quicken', "select z_pk from ztag where zquickenid=$categoryId", ["Int"])
        def categoryPk = rows[0][0]

        rows = execQuery('quicken', "select z_pk from zaccount where zname='$accountName'", ["Int"])
        if (!rows[0] || !rows[0][0]) {
            println "Error retreiving row id for $accountName"
            return
        }
        def accountId = rows[0][0]
        
        rows = execQuery('quicken', "select max(z_pk) from zcashflowtransactionentry", ["Int"])
        def max = rows[0][0] + 1
        def stmt = "insert into zcashflowtransactionentry ( Z_PK, Z_ENT, Z_OPT, ZQUICKENID, ZDELETIONCOUNT, ZMISCFLAGS, ZSEQUENCENUMBER, ZCOMMUNITYTAG, ZCATEGORYTAG, ZPARENT, Z44_PARENT, ZCREATIONDATE, ZMODIFICATIONDATE, ZAMOUNT, ZNOTE, ZIMPORTSOURCEFILEID, ZTRANSFER, ZDATASOURCE, ZIMPORTSOURCEID, ZSYNCID ) values ( $max, 9, 2, $max, 0, 0, 1, null, $categoryPk, $max, 44, '317484794.171017', '317484794.171017', $amount, $note, null, null, null, null, null)"
        execUpdate('quicken', stmt)
    
        rows = execQuery('quicken', "select max(z_pk) from ztransaction", ["Int"])
        max = rows[0][0] + 1
        stmt = "insert into ztransaction ( Z_PK, Z_ENT, Z_OPT, ZLOCKED, ZUSERSTATUSOVERRIDE, ZDELETIONCOUNT, ZMATCHED, ZTYPE, ZUNIQUEID, ZEXCLUDEFROMREPORTS, ZQUICKENID, ZDOWNLOADSESSION, ZTARGETACCOUNT, ZACCOUNT, ZLABEL, ZRECONCILESTATUS, ZFLAG, ZMACHINEGENERATEDPAYEESOURCE, ZMACHINEGENERATEDCATEGORYSOURCE, ZMACHINEGENERATEDTAGSOURCE, ZMISCFLAGS, ZFIPAYEE, ZACTION, ZUSERPAYEE, ZAUTOPOST, ZAMOUNTCALCULATOR, ZMODIFICATIONDATE, ZENTEREDDATE, ZPOSTEDDATE, ZCREATIONDATE, ZBUDGETFLEXIBILITY, ZAMOUNT, ZIMPORTSOURCEID, ZONLINEBANKINGSERVERID, ZFITRANSACTIONID, ZDATASOURCE, ZREFERENCE, ZSYNCID, ZIMPORTSOURCEFILEID, ZMATCHSOURCE, ZCHECKNUMBER, ZFINOTE, ZNOTE, ZLOCATION, ZCHECKPROPERTIES, ZPREMATCHPROPERTIES, ZRECURRENCEEND ) values ( $max, 44, 2, 0, null, 0, null, null, null, 0, $max, null, null, $accountId, null, null, null, null, null, null, 0, null, null, $payeeId, null, null, '317484794.171017', $date, null, '317484794.171017', null, $amount, null, null, null, null, null, null, null, null, null, null, $note, null, null, null, null)"
        execUpdate('quicken', stmt)
        bumpPk('Transaction')
        bumpPk('CashFlowTransactionEntry')
    
        addAttachment("/Users/eric/Documents/Neat Library.nrmlib/${pdf}.pdf", max)
    }


    // Run sqlite query
    def execQuery(id, query, types) {
        def result = []
        def conn = cnxns[id]
        Statement stmt = conn.createStatement()
        ResultSet rs = stmt.executeQuery(query)
        while (rs.next()) {
           def row = []
           result << row
           def count = 0
           types.each { row << rs."get$it"(++count) }
        }
        rs.close()
        //conn.close()
        result
    }


    // Not used, but maybe useful
    def exec(cmd) {
        println "execing $cmd"
        def proc = cmd.execute()
        def outLog = new StringWriter()
        def errLog = new StringWriter()
        Thread.start { outLog << proc.in }
        Thread.start { errLog << proc.err }
        proc.out.close()
        proc.waitForOrKill(10 * 60 * 1000)
        println("outLog is $outLog")
        println("errLog is $errLog")
    }


    def computeQuickenAccountTypeFromNeatPaymentType(pmtType) {
        switch (pmtType.toLowerCase()) {
            case ~/cash/                    : return 'CASH'
            case ~/debit card/              : return 'CHECKING'
            case ~/visa/                    :
            case ~/master\s*card/             :
            case ~/discover/                : return 'CREDITCARD'
            default                         : return 'CASH'
        }
    }


    def gatherItems(limit) {
        def items = [:]
        def limitClause = ''
        if (limit) limitClause = "limit $limit"
        def itemTuples = execQuery('neat', "select i.Z_PK, i.ZNEATPDFNAME from ZNRMBASEITEM as i, ZNRMFIELD as a where i.Z_PK = a.ZFIELDSET and a.ZATTRIBUTES=17 and i.ZCLASSNAME like '%eceipt' $limitClause", ["Int", "String"])
        itemTuples.each {
           def id, pdf
           ( id, pdf ) = it
           print '.'
           def query = "select Z_PK from ZNRMFIELDSET where ZITEM=$id"
           def values = execQuery('neat', query, [ 'Int' ])
           def fieldsetId = values ? values[0][0] : null
           def item = [ 'pdf' : pdf ]
           items[id] = item
           labelToFieldId.each { label, fieldId ->
               def type = labelToType[label]
               def ztype = "Z${type.toUpperCase()}VALUE"
               // Hacks... Seems rs.getDecimal() doesn't work, etc
               if (type == 'Decimal') type = 'Float'
               if (type == 'Boolean') ztype = 'ZBOOLVALUE'
               if (type == 'Timestamp') ztype = 'ZDATEVALUE'
               query = "select $ztype from ZNRMFIELD where ZFIELDSET = $fieldsetId and ZATTRIBUTES=$fieldId"
               values = execQuery('neat', query, [ type ])
               def value = values ? values[0][0] : null
               if (value instanceof java.sql.Timestamp) {
                    item['Timestamp'] = value.getTime()
                    item['Date'] = new Date((value.getTime() + 978282000) * 1000)
               } else 
                   item[label] = value        
               if (label == 'Payment Type' && value) {
                   item['QuickenAcctType'] = computeQuickenAccountTypeFromNeatPaymentType(value)
               }
           }
        }
        println ''
        items
    }


    def gatherNids() {
        def rows = execQuery('quicken', 'Select ZNOTE from ztransaction where zdeletioncount=0', ['String'])
        nids = new HashSet()
        rows.each {
            if (it[0] =~ /nid=/) { 
                def note = it[0]
                def i = note.indexOf('nid=')
                def nid = note.substring(i+4, i+40)
                nids << nid
            }
        }
    }


    def sqlEscape(s) {
       if (!s) return s
       s.replaceAll("'", "")
    }


    def addCashTransactionForNeatItem(item) {
        if (!item.pdf) return
        def nid = item.pdf - '.pdf'
        if (nids.contains(nid)) return
        def note = "nid=$nid"
        if (item.Notes) note = "$item.Notes | " + note
        if (item.Amount == null) {
            println "Skipping transaction date=${item.Date}, vendor=${item.Vendor}, Payment Type=${item.'Payment Type'} due to missing Amount"
            return
        }
        if (!item.QuickenAcctType) {
            println "Skipping transaction date=${item.Date}, vendor=${item.Vendor}, amount=${item.Amount} due to missing Payment Type"
            return
        }
        addCashTransaction(item.Timestamp, sqlEscape(item.Vendor), item.Category, item.Amount * -1, nid, sqlEscape(note), item['Payment Type'], item.QuickenAcctType)
    }


    def addAccount(accountName, accountType) {
        def rows = execQuery('quicken', "select z_pk from zaccounttype where zname='$accountType'", ["Int"])
        if (rows == null || rows[0] == null) {
            println("Exception in addAccount: can't add account of type $accountType")
            return
        }
        def acctTypeId = rows[0][0]

        rows = execQuery('quicken', "select max(z_pk) from zaccount", ["Int"])
        def pk = 1
        if ((rows != null) && (rows[0] != null))
            pk = rows[0][0] + 1
        def olBankingType = (accountType == 'CHECKING') ? "'CHECKING'" : null;

        // Bump all Z_OPTS of zaccountType, bump the one for the account type even one more
        rows = execQuery('quicken', "select z_pk from zaccounttype", ["Int"])
        def acctTypePks = rows.collect { it[0] }
        acctTypePks.each {
            def bumpBy = (it == acctTypeId) ? 2 : 1
            def updateStmt = "update zaccounttype set z_opt=z_opt+$bumpBy where z_pk=$it"
            execUpdate('quicken', updateStmt)
        }

        def insertStmt = "insert into zaccount ( Z_PK, Z_ENT, Z_OPT, ZACTIVE, ZONLINEBANKINGACCOUNTISINBIDCHANGE, ZUSEDINAUTOUPDATES, ZLIQUIDITY, ZMOSTRECENTDOWNLOADSESSIONNUMBER, ZONLINEBANKINGACCOUNTINFOSESSIONNEEDED, ZDELETIONCOUNT, ZONLINEBANKINGACCOUNTISENABLED, ZSCHEDULELOOKAHEADDAYS, ZONLINEBANKINGCHANGEPINFIRSTCOMPLETED, ZSCHEDULELOOKAHEADENABLED, ZTAXABLE, ZUSEDINREPORTS, ZLASTCHECKNUM, ZQUICKENID, ZTYPE, ZFINANCIALINSTITUTION, ZALLTRANSMARKEDASREVIEWEDDATE, ZONLINEBANKINGLASTCONNECTEDDATE, ZCREATIONDATE, ZMODIFICATIONDATE, ZONLINEBANKINGLEDGERBALANCEDATE, ZONLINEBANKINGLEDGERBALANCEAMOUNT, ZCREDITLIMIT, ZINTERESTRATE, ZONLINEBANKINGCUSTOMERID, ZIMPORTSOURCEID, ZONLINEBANKINGSERVERID, ZNOTES, ZREGIONCODE, ZIMPORTSOURCEFILEID, ZONLINEBANKINGDTEND, ZONLINEBANKINGLASTTOKEN, ZONLINEBANKINGROUTINGNUMBER, ZONLINEBANKINGCONNECTIONTYPE, ZONLINEBANKINGLASTUUID, ZSYNCID, ZONLINEBANKINGACCOUNTNUMBER, ZNAME, ZDATASOURCE, ZONLINEBANKINGOFXACCOUNTTYPE, ZONLINEBANKINGORG, ZUSERDESCRIPTION, ZONLINEBANKINGPREVIOUSBID, ZCURRENCY, ZCHECKPRINTINGPROPERTIES, ZRECONCILEINFO, ZFILTERS, ZONLINEBANKINGDOWNLOADSESSIONSTATUS ) values ( $pk, 1, 2, 1, 0, 1, null, null, 0, 0, 0, 15, 0, 1, 0, 1, null, $pk, $acctTypeId, null, null, null, 318196297.7017, 318196297.7017, null, null, 0, null, null, null, null, null, null, null, null, 0, null, 'qol', null, null, null, '" + accountName + "', null, $olBankingType, 0, null, null, 'USD', null, null, null, null)"
        execUpdate('quicken', insertStmt)
    }

  
    def maybeAddAccount(accountName, accountType) {
        def rows = execQuery('quicken', "select z_pk from zaccount where zname='$accountName'", ["Int"])
        if (rows.size() == 0) {
            addAccount(accountName, accountType)
        }
    }


    def printNeatItems(items) {
        items.each { k, v ->
            println "$k :"
            v.each { k2, v2 ->
                println "    $k2 : $v2"
            }
        }
    }


    /**
      * Main method
      */
    def doTransfer(limit) {
        println 'Gathering quicken nids...'
        gatherNids()
        print 'Gathering neat items...'
        def neatItems = gatherItems(limit)
        println 'Creating transactions...'
        neatItems.each { k, v -> addCashTransactionForNeatItem(v) }
    } 


    def TransferUtil(neatDataDir, quickenDataDir) {
        this.neatDataDir    = neatDataDir
        this.quickenDataDir = quickenDataDir

        Class.forName("org.sqlite.JDBC")
        def neatJdbc    = "jdbc:sqlite:$neatDataDir/NRMData.nrms"
        def quickenJdbc = "jdbc:sqlite:$quickenDataDir/data"
        dbs.neat        = Sql.newInstance(neatJdbc, null, null, 'org.sqlite.JDBC')
        dbs.quicken     = Sql.newInstance(quickenJdbc, null, null, 'org.sqlite.JDBC')
        cnxns.neat      = DriverManager.getConnection(neatJdbc)
        cnxns.quicken   = DriverManager.getConnection(quickenJdbc)
    }    
}

