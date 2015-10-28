import hudson.plugins.copyartifact.SpecificBuildSelector
import hudson.plugins.copyartifact.LastCompletedBuildSelector

// Job Parameters
def nodeExecutor         = executor
def parentJob            = parent_job
def parentJobBuild       = parent_job_build
def marvinConfigFile     = marvin_config_file

def FRESH_DB_DUMP = 'fresh-db-dump.sql'
def DIRTY_DB_DUMP = 'dirty-db-dump.sql'
def DB_DUMP_DIFF  = 'db-dump-diff.txt'

node(nodeExecutor) {
  sh 'rm -rf ./*'

  copyFilesFromParentJob(parentJob, parentJobBuild, [FRESH_DB_DUMP])

  sh  "cp /data/shared/marvin/${marvinConfigFile} ./"

  collectLogFiles('root@cs1', ['~tomcat/vmops.log*', '~tomcat/api.log*'], '.')
  archive 'vmops.log*, api.log*'
  sh '/data/vm-easy-deploy/remove_vm.sh -f cs1'

  // TODO: replace hardcoded box names
  ['kvm1', 'kvm2'].each { host ->
    def hostLogDir = "${host}-agent-logs/"
    sh "mkdir ${hostLogDir}"
    collectLogFiles("root@${host}", ['/var/log/cloudstack/agent/agent.log*'], hostLogDir)
    archive hostLogDir
    sh "/data/vm-easy-deploy/remove_vm.sh -f ${host}"
  }


  dumpDb(DIRTY_DB_DUMP)
  diffDbDumps(FRESH_DB_DUMP, DIRTY_DB_DUMP, DB_DUMP_DIFF)
  archive DB_DUMP_DIFF
}

// ----------------
// Helper functions
// ----------------

// TODO: move to library
def copyFilesFromParentJob(parentJob, parentJobBuild, filesToCopy) {
  def buildSelector = { build ->
    if(build == null || build.isEmpty() || build.equals('last_completed')) {
      new LastCompletedBuildSelector()
    } else {
      new SpecificBuildSelector(build)
    }
  }

  step ([$class: 'CopyArtifact',  projectName: parentJob, selector: buildSelector(parentJobBuild), filter: filesToCopy.join(', ')]);
}

def collectLogFiles(partialTarget, files, destination) {
  files.each { f ->
    try {
      scp("${partialTarget}:${f}", destination)
    } catch(e) {
      echo "Failed to collect file '${f}' from ${partialTarget}"
    }
  }
}

def dumpDb(dumpFile) {
  sh "rm -f ${dumpFile}"
  writeFile file: 'dumpDb.sh', text: "mysqldump -u root cloud > ${dumpFile}"
  scp('dumpDb.sh', 'root@cs1:./')
  ssh('root@cs1', 'chmod +x dumpDb.sh; ./dumpDb.sh')
  scp("root@cs1:./${dumpFile}", '.')
  archive dumpFile
}

def diffDbDumps(fresh, dirty, diffFile) {
  sh "diff ${fresh} ${dirty} > ${diffFile}"
}

def scp(source, target) {
  sh "scp -i ~/.ssh/mccd-jenkins.pem -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -q -r ${source} ${target}"
}

def ssh(target, command) {
  sh "ssh -i ~/.ssh/mccd-jenkins.pem -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -q ${target} \"${command}\""
}

def mysqlScript(host, user, pass, db, script) {
  def passOption = pass !=  '' ? "-p${pass}" : ''
  sh "mysql -h${host} -u ${user} ${passOption} ${db} < ${script}"
}
