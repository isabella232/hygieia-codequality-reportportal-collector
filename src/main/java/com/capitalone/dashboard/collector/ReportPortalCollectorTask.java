package com.capitalone.dashboard.collector;


import com.capitalone.dashboard.model.ReportPortalCollector;
import com.capitalone.dashboard.model.ReportPortalProject;
import com.capitalone.dashboard.model.ReportResult;
import com.capitalone.dashboard.repository.BaseCollectorRepository;
import com.capitalone.dashboard.repository.ComponentRepository;
import com.capitalone.dashboard.repository.ReportPortalCollectorRepository;
import com.capitalone.dashboard.repository.ReportPortalProjectRepository;
import com.capitalone.dashboard.repository.ReportResultRepository;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.bson.types.ObjectId;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class ReportPortalCollectorTask extends CollectorTask<ReportPortalCollector> {
    @SuppressWarnings({ "PMD.UnusedPrivateField", "unused" })
    private static final Log LOG = LogFactory.getLog(ReportPortalCollectorTask.class);

    private final ReportPortalCollectorRepository reportCollectorRepository;
    private final ReportPortalProjectRepository reportProjectRepository;
    private final ReportPortalSettings reportSettings;
    private final ReportPortalClient reportClient;
    private final ReportResultRepository reportRepository;
   
    @Autowired
    public ReportPortalCollectorTask(TaskScheduler taskScheduler,
    		ReportResultRepository reportRepository,
    						  ReportPortalClient reportClient,
                              ReportPortalCollectorRepository reportCollectorRepository,
                              ReportPortalProjectRepository reportProjectRepository,                            
                              ReportPortalSettings reportSettings,
                              ComponentRepository dbComponentRepository) {
        super(taskScheduler, "reportportal");
        this.reportCollectorRepository = reportCollectorRepository;
        this.reportProjectRepository = reportProjectRepository;
        this.reportSettings = reportSettings;
        this.reportClient=reportClient;
        this.reportRepository=reportRepository;
    }

    @Override
    public ReportPortalCollector getCollector() {
        return ReportPortalCollector.prototype(reportSettings);
    }

    @Override
    public BaseCollectorRepository<ReportPortalCollector> getCollectorRepository() {
        return reportCollectorRepository;
    }

    @Override
    public String getCron() {
        return reportSettings.getCron();
    }

    @Override
    public void collect(ReportPortalCollector collector) {
        long start = System.currentTimeMillis();

        Set<ObjectId> udId = new HashSet<>();
        udId.add(collector.getId());
        List<ReportPortalProject> existingProjects = reportProjectRepository.findByCollectorIdIn(udId);
        List<ReportPortalProject> latestProjects = new ArrayList<>();

        if (!CollectionUtils.isEmpty(collector.getReportPortalServers())) {
            
            for (int i = 0; i < collector.getReportPortalServers().size(); i++) {

                String instanceUrl = collector.getReportPortalServers().get(i);
                String projectName=collector.getProjectName();
                logBanner(instanceUrl);
                List<ReportPortalProject> projects = reportClient.getProjectData(instanceUrl,projectName);
                latestProjects.addAll(projects);

                int projSize = ((CollectionUtils.isEmpty(projects)) ? 0 : projects.size());
                log("Fetched launches   " + projSize, start);

                addNewProjects(projects, existingProjects, collector);
              
                List<ReportPortalProject> projectstoupdate =reportProjectRepository.findByCollectorIdIn(udId);
                updateReportInfo(projectstoupdate,collector,instanceUrl);

                
                

                log("Finished", start);
            }
        }
    }


    private void updateReportInfo(List<ReportPortalProject> projects, ReportPortalCollector collector,String instanceUrl) {
		// TODO Auto-generated method stub
    	int count=0;
    	long start = System.currentTimeMillis();
    	List<ReportResult> newTests=new ArrayList<>();
    	List<ReportResult> updateTests=new ArrayList<>();
    	for(ReportPortalProject project: projects) {
    		
        	
    	
    		List<ReportResult> tests= reportClient.getTestData(collector,project);
    		for(ReportResult test:tests) {
    			Map<String, Object> results=test.getResults();
    			test.setExecutionId(project.getLaunchNumber());
    			
    			ReportResult foundTest=reportRepository.findBytestId(test.getTestId());
    			if(foundTest==null) {
    				newTests.add(test);
    				count++;
    			}		
    			else {
    				foundTest.setResults(results);
    				foundTest.setExecutionId(project.getLaunchNumber());
    				foundTest.setTestCapabilities(test.getTestCapabilities());
    				updateTests.add(foundTest);
    			}
    			
    		}
    		
    	}
        if (!CollectionUtils.isEmpty(newTests)) {
            reportRepository.save(newTests);
        }
        if (!CollectionUtils.isEmpty(updateTests)) {
            reportRepository.save(updateTests);
        }		
        
        log("New Tests", start, count);
    	
	}

	

	private void addNewProjects(List<ReportPortalProject> projects, List<ReportPortalProject> existingProjects, ReportPortalCollector collector) {
        long start = System.currentTimeMillis();
        int count = 0;
        List<ReportPortalProject> newProjects = new ArrayList<>();
        List<ReportPortalProject> updateProjects = new ArrayList<>();
        for (ReportPortalProject project : projects) {
            String niceName = getNiceName(project,collector);
            Map<String, Object> Options=project.getOptions();
            if (!existingProjects.contains(project)) {
                project.setCollectorId(collector.getId());
                project.setEnabled(false);
                project.setDescription(project.getProjectName());
                project.setNiceName(niceName);
                project.setLastUpdated(start);
                newProjects.add(project);
                count++;
            }else{
                int index = existingProjects.indexOf(project);
                ReportPortalProject s = existingProjects.get(index);
                    s.setNiceName(niceName);
                    s.setLastUpdated(start);
                    Options.put("launchId",project.getLaunchId());
                    s.setOptions(Options);
                    s.setLaunchNumber(project.getLaunchNumber());
                   
         
                    updateProjects.add(s);
             
            }
        }
        if (!CollectionUtils.isEmpty(newProjects)) {
            reportProjectRepository.save(newProjects);
        }
        if (!CollectionUtils.isEmpty(updateProjects)) {
            reportProjectRepository.save(updateProjects);
        }
        log("New launches", start, count);
    }

    private String getNiceName(ReportPortalProject project, ReportPortalCollector reportCollector){

        if (org.springframework.util.CollectionUtils.isEmpty(reportCollector.getReportPortalServers())) return "";
        List<String> servers = reportCollector.getReportPortalServers();
        List<String> niceNames = reportCollector.getNiceNames();
        if (org.springframework.util.CollectionUtils.isEmpty(niceNames)) return "";
        for (int i = 0; i < servers.size(); i++) {
            if (servers.get(i).equalsIgnoreCase(project.getInstanceUrl()) && (niceNames.size() > i)) {
                return niceNames.get(i);
            }
        }
        return "";

    }

    @SuppressWarnings("unused")
	private boolean isNewProject(ReportPortalCollector collector, ReportPortalProject application) {
        return reportProjectRepository.findReportProject(
                collector.getId(), application.getInstanceUrl(), application.getProjectId()) == null;
    }

   
    
    
    
    

}
