package com.attask.jenkins.dashboard;

import hudson.Extension;
import hudson.model.*;
import hudson.tasks.junit.TestResultAction;
import hudson.util.RunList;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

import javax.servlet.ServletException;
import java.io.IOException;
import java.util.*;
import java.util.logging.Logger;

/**
 * A view that shows a table of builds that are related (determined by build description and a regex).
 *
 * User: joeljohnson
 * Date: 2/9/12
 * Time: 9:56 AM
 */
public class PipelineDashboard extends View {
	public static Logger LOGGER = Logger.getLogger(PipelineDashboard.class.getSimpleName());
	public List<String> jobs;
    public String descriptionRegex;
    public int descriptionRegexGroup;
    public int numberDisplayed;
	public String firstColumnName;

	@DataBoundConstructor
	public PipelineDashboard(String name) {
		super(name);
	}
	
	public static Collection<String> getAllJobs() {
		return Hudson.getInstance().getJobNames();
	}

	@Override
	protected void submit(StaplerRequest request) throws ServletException, Descriptor.FormException, IOException {
		String jobsParameter = request.getParameter("_.jobs");
		this.jobs = new ArrayList<String>() {
			//Overriding to make it show up pretty in jenkins
			public String toString() {
				return join(this, ", ");
			}
		};
		if(jobsParameter != null) {
			for (String job : Arrays.asList(jobsParameter.split(","))) {
				jobs.add(job.trim());
			}
		}

		this.descriptionRegex = request.getParameter("_.descriptionRegex");
		String descriptionRegexGroup = request.getParameter("_.descriptionRegexGroup");
		if(descriptionRegexGroup != null) {
			this.descriptionRegexGroup = Integer.parseInt(descriptionRegexGroup);
			if(this.descriptionRegexGroup < 0) {
				this.descriptionRegexGroup = 0;
			}
		} else {
			this.descriptionRegexGroup = 0;
		}


		String numberDisplayed = request.getParameter("_.numberDisplayed");
		if(numberDisplayed == null || numberDisplayed.isEmpty()) {
			this.numberDisplayed = 5;
		} else {
			this.numberDisplayed = Integer.parseInt(numberDisplayed);
		}

		this.firstColumnName = request.getParameter("_.firstColumnName");
	}

	/**
	 * Finds all the builds that matches the criteria in the settings and organizes them in rows and columns.
	 * @return The list of Rows. Each row containing information about builds whose descriptions match based on the
	 * 			regex provided. The list is sorted by build date starting with the most recent.
	 */
	@SuppressWarnings("UnusedDeclaration")
	public List<Row> getDisplayRows() {
		LOGGER.info("getDisplayRows starting");

		Map<String, Build[]> map = findMatchingBuilds();
		LOGGER.info("map size: " + map.size());

		List<Row> result = generateRowData(User.current(), map);
		LOGGER.info("result size: " + result.size());

		return result;
	}

	private Map<String, Build[]> findMatchingBuilds() {
		Map<String, Build[]> map = new HashMap<String, Build[]>();
		for (String jobName : jobs) {
			try {
				Job job = (Job) Hudson.getInstance().getItem(jobName);
				RunList builds = job.getBuilds();
				for (Object buildObj : builds) {
					Build build = (Build)buildObj;
					String buildName = build.getDisplayName();
					String buildDescription = build.getDescription();
					if(buildDescription == null || "".equals(buildDescription.trim())) {
						LOGGER.info(job.getDisplayName() + " " +build.getDisplayName() + " Description " + buildDescription);
						LOGGER.info("There was no build description. Skipping");
						continue;
					}
					buildDescription = buildDescription.replaceAll("(\n|\r)", " "); //normalize whitespace

					LOGGER.info(job.getDisplayName() + " " +build.getDisplayName() + " Description " + buildDescription);
					if(buildDescription.matches(descriptionRegex)) {
						LOGGER.info("Matched the description of " + jobName + " " + buildName);
						String key = buildDescription.replaceFirst(descriptionRegex, "$" + this.descriptionRegexGroup);
						LOGGER.info("\tResult: ``" + key + "``");
						if(!map.containsKey(key)) {
							LOGGER.info("Entry " + key + " doesn't exist, creating a new one");
							map.put(key, new Build[jobs.size()]);
						}
						map.get(key)[jobs.indexOf(jobName)] = build;
					}
				}
			} catch(Throwable t) {
				LOGGER.severe("Error while generating the map: " + t.getMessage() + "\n" + join(Arrays.asList(t.getStackTrace()), "\n"));
			}
		}
		return map;
	}

	private List<Row> generateRowData(User currentUser, Map<String, Build[]> map) {
		Hudson hudson = Hudson.getInstance();
		SortedSet<Row> rows = new TreeSet<Row>(new Comparator<Row>() {
			public int compare(Row row1, Row row2) {
				if(row1 == row2) return 0;
				if(row1 == null) return 1;
				if(row2 == null) return -1;
				return -row1.getDate().compareTo(row2.getDate());
			}
		});

		for (String rowName : map.keySet()) {
			try {
				LOGGER.info("Row " + rowName);
				Build[] builds = map.get(rowName);
				List<Column> columns = new LinkedList<Column>();
				Date date = null;
				String displayName = rowName;
				boolean isCulprit = false;

				for (Build build : builds) {
					if(build != null) {
						LOGGER.info("\t" + build.getDisplayName() + " " + build.getDescription());
						if(date == null) date = build.getTime();

						String testResult = "";
						TestResultAction testResultAction = build.getAction(TestResultAction.class);
						if(testResultAction != null) {
							int failures = testResultAction.getFailCount();
							testResult = "(" + failures + " failures" + ")";
						}

						String rowDisplayName = testResult.isEmpty() ? build.getDisplayName() : testResult;

						columns.add(new Column(rowDisplayName, build.getUrl() + "testReport", hudson.getRootUrl() +"/static/832a5f9d/images/24x24/" + build.getBuildStatusUrl()));
					} else {
						LOGGER.info("\tAdded empty column");
						columns.add(Column.EMPTY);
					}
					//noinspection StringEquality
					if(displayName == rowName && build.getDescription() != null && !build.getDescription().trim().isEmpty()) { // I really do want to do reference equals and not value equals.
						displayName = build.getDescription();
						isCulprit = getUserIsCulprit(currentUser, build);
					}
				}
				if(date == null) date = new Date();

				rows.add(new Row(date, rowName, displayName, columns, isCulprit));
			} catch (Throwable t) {
				LOGGER.severe("Error while generating the list: " + t.getMessage() + "\n" + join(Arrays.asList(t.getStackTrace()), "\n"));
			}
		}
		List<Row> result = new LinkedList<Row>();

		int i = 0;
		for (Row row : rows) {
			result.add(row);
			i++;
			if(i >= numberDisplayed || i >= rows.size()) {
				break;
			}
		}
		return result;
	}

	private boolean getUserIsCulprit(User currentUser, Build build) {
		if(currentUser == null || build == null) return false;

		String description = build.getDescription();
		if(description.contains(currentUser.getFullName()) || description.contains(currentUser.getId())) {
			return true;
		}

		//noinspection unchecked
		for (User culprit : (Set<User>)build.getCulprits()) {
			if(culprit.getId().equals(currentUser.getId())) {
				return true;
			}
		}
		
		return false;
	}

	private String getStackTraceMethod(StackTraceElement[] stackTrace) {
		return join(Arrays.asList(stackTrace), "\n");
	}

	/**
	 * Generates a flat string
	 * @param collection
	 * @param separator
	 * @return
	 */
	private String join(Collection<?> collection, String separator) {
		if(collection == null) return "";
		if(separator == null) separator = "";

		StringBuilder sb = new StringBuilder();
		for (Object s : collection) {
			sb.append(s).append(separator);
		}
		if(sb.length() > 0) {
			return sb.substring(0, sb.length() - separator.length());
		}
		return "";
	}


	@Override
	public Collection<TopLevelItem> getItems() {
		return Collections.emptyList();
	}

	@Override
	public boolean contains(TopLevelItem item) {
		return false;
	}

	@Override
	public void onJobRenamed(Item item, String oldName, String newName) {
		Collections.replaceAll(jobs, oldName, newName);
	}

	@Override
	public synchronized Item doCreateItem(StaplerRequest request, StaplerResponse response) throws IOException, ServletException {
		Item item = Hudson.getInstance().doCreateItem(request, response);
		if (item != null) {
			jobs.add(item.getName());
			owner.save();
		}
		return item;
	}

	@Override
	public String toString() {
		return super.toString() + " { " +
				"description: " + this.description + ", " +
				"descriptionRegex: " + this.descriptionRegex + ", " +
				"firstColumnName: " + this.firstColumnName + ", " +
				"numberDisplayed: " + this.numberDisplayed + ", " +
				"jobs: [" + this.join(this.jobs, ", ") + "]" +
		"}";
	}

	@Extension
	public static final class DescriptorImpl extends ViewDescriptor {
		@Override
		public String getDisplayName() {
			return "Pipeline Dashboard";
		}
	}
}

