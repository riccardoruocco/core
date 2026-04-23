/*
*
* Copyright (c) 2025 dotCMS LLC
* Use of this software is governed by the Business Source License included
* in the LICENSE file found at in the root directory of software.
* SPDX-License-Identifier: BUSL-1.1
*
*/

package com.dotcms.enterprise.publishing.staticpublishing;

import com.dotcms.enterprise.LicenseUtil;
import com.dotcms.enterprise.license.LicenseLevel;
import com.dotcms.enterprise.publishing.bundlers.*;
import com.dotcms.publisher.assets.business.PushedAssetsAPI;
import com.dotcms.publisher.business.*;
import com.dotcms.publisher.endpoint.bean.PublishingEndPoint;
import com.dotcms.publisher.endpoint.business.PublishingEndPointAPI;
import com.dotcms.publisher.environment.bean.Environment;
import com.dotcms.publisher.environment.business.EnvironmentAPI;
import com.dotcms.publisher.pusher.PushUtils;
import com.dotcms.publishing.*;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.dotcms.vanityurl.business.VanityUrlAPI;
import com.dotcms.vanityurl.model.CachedVanityUrl;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.TrueFileFilter;
import com.dotmarketing.beans.Host;
import com.dotmarketing.business.APILocator;
import com.dotmarketing.exception.DotDataException;
import com.dotmarketing.portlets.contentlet.business.HostAPI;
import com.dotmarketing.portlets.languagesmanager.model.Language;
import com.dotmarketing.util.*;
import com.google.common.annotations.VisibleForTesting;
import io.vavr.Lazy;
import org.apache.logging.log4j.ThreadContext;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;
import javax.servlet.http.HttpServletResponse;

/**
 * Similar to the TimeMachine this will be use several bundlers to create a static copy of the site.
 * Usually the site generated will be push to a bucket in a remote server, for instance AWS S3.
 *
 * @author jsanca
 */
@PublisherConfiguration (isStatic = true)
public class AWSS3Publisher extends Publisher {

    private static final int REQUIRED_LICENSE_LEVEL                      = LicenseLevel.PLATFORM.level; // Super Prime license.
    public static final String DOTCMS_PUSH_AWS_S3_BUCKET_ID              = "aws_bucket_name";
    public static final String DOTCMS_PUSH_AWS_S3_BUCKET_ROOT_PREFIX     = "aws_bucket_folder_prefix";
    public static final String DOTCMS_PUSH_AWS_S3_BUCKET_REGION          = "aws_bucket_region";
    public static final String DOTCMS_PUSH_AWS_S3_BUCKET_VALIDATION_NAME = "aws_validation_bucket";
    public static final String DOTCMS_PUSH_AWS_S3_ENDPOINT               = "aws_endpoint";
    public static final String DOTCMS_PUSH_AWS_S3_BUCKET_REGION_DEFAULT  = "us-east-1";
    public static final String DOTCMS_PUSH_AWS_S3_TOKEN                  = "aws_access_key";
    public static final String DOTCMS_PUSH_AWS_S3_SECRET                 = "aws_secret_access_key";
    public static final String PROTOCOL_AWS_S3                           = "awss3";
    public static final String DEFAULT_BUCKET_NAME                       = "dot-bucket-default";
    public static final String STATIC_PUSH_S3_VANITY_ALIAS_ENABLED       = "STATIC_PUSH_S3_VANITY_ALIAS_ENABLED";

    private static final String CREATED_BUCKETS                          = "createdBuckets";
    private static final Lazy<Boolean> S3_VANITY_ALIAS_ENABLED =
            Lazy.of(() -> Config.getBooleanProperty(STATIC_PUSH_S3_VANITY_ALIAS_ENABLED, false));

    private final HostAPI hostAPI;
    private final PublishAuditAPI publishAuditAPI;
    private final EnvironmentAPI environmentAPI;
    private final PublishingEndPointAPI publisherEndPointAPI;
    private final PushedAssetsAPI pushedAssetsAPI;
    private final VanityUrlAPI vanityUrlAPI;
    private final S3VanityAliasMapRepository s3VanityAliasMapRepository;

    /**
     * Class constructor.
     */
    public AWSS3Publisher() {
    	this.hostAPI = APILocator.getHostAPI();
        this.publishAuditAPI = PublishAuditAPI.getInstance();
        this.environmentAPI = APILocator.getEnvironmentAPI();
        this.publisherEndPointAPI = APILocator.getPublisherEndPointAPI();
        this.pushedAssetsAPI = APILocator.getPushedAssetsAPI();
        this.vanityUrlAPI = APILocator.getVanityUrlAPI();
        this.s3VanityAliasMapRepository = new S3VanityAliasMapRepository();
    }

    /**
     * Test driven constructor
     * @param hostAPI
     * @param publishAuditAPI
     * @param environmentAPI
     * @param publisherEndPointAPI
     * @param pushedAssetsAPI
     */
    @VisibleForTesting
    public AWSS3Publisher(final HostAPI hostAPI,
            final PublishAuditAPI publishAuditAPI,
            final EnvironmentAPI environmentAPI,
            final PublishingEndPointAPI publisherEndPointAPI,
            final PushedAssetsAPI pushedAssetsAPI) {
        this(hostAPI, publishAuditAPI, environmentAPI, publisherEndPointAPI, pushedAssetsAPI,
                APILocator.getVanityUrlAPI(), new S3VanityAliasMapRepository());
    }

    /**
     * Extended constructor for tests and explicit dependency composition.
     *
     * @param hostAPI host API.
     * @param publishAuditAPI publish audit API.
     * @param environmentAPI environment API.
     * @param publisherEndPointAPI publishing endpoint API.
     * @param pushedAssetsAPI pushed assets API.
     * @param vanityUrlAPI Vanity URL API.
     * @param s3VanityAliasMapRepository S3 vanity mapping repository.
     */
    @VisibleForTesting
    public AWSS3Publisher(final HostAPI hostAPI,
            final PublishAuditAPI publishAuditAPI,
            final EnvironmentAPI environmentAPI,
            final PublishingEndPointAPI publisherEndPointAPI,
            final PushedAssetsAPI pushedAssetsAPI,
            final VanityUrlAPI vanityUrlAPI,
            final S3VanityAliasMapRepository s3VanityAliasMapRepository) {
        this.hostAPI = hostAPI;
        this.publishAuditAPI = publishAuditAPI;
        this.environmentAPI = environmentAPI;
        this.publisherEndPointAPI = publisherEndPointAPI;
        this.pushedAssetsAPI = pushedAssetsAPI;
        this.vanityUrlAPI = vanityUrlAPI;
        this.s3VanityAliasMapRepository = s3VanityAliasMapRepository;
    }

    /**
	 * Safety check to review that the current dotCMS instance is assigned the
	 * correct license level to perform this functionality.
	 */
    private void checkLicense() {
        if(LicenseUtil.getLevel() < REQUIRED_LICENSE_LEVEL) {
            throw new RuntimeException("Need an enterprise licence to run this functionality");
        }
    } //checkLicense

    @Override
    public PublisherConfig init(PublisherConfig config) throws DotPublishingException {

        this.checkLicense();

        try {
	        config = (PublisherConfig) config.clone();
	        config.setStatic(true);
	        config.put(DOT_STATIC_DATE, new Date());

	        this.config = super.init(config);
        } catch (CloneNotSupportedException e){}

        return this.config;
    } // init.

    private Map<String, Boolean> shouldForcePushCache = new HashMap<>();

    @Override
    public boolean shouldForcePush(String hostId, long languageId) {
    	String cacheKey = hostId +"/"+ languageId;
    	Boolean cachedValue = shouldForcePushCache.get(cacheKey);
    	if (cachedValue != null) {
    		return cachedValue;
    	}

    	boolean result = false;
    	try {
        	Host host = hostAPI.find(hostId, APILocator.getUserAPI().getSystemUser(), false);
            if(host != null) {
	            List<Environment> environments = this.environmentAPI.findEnvironmentsByBundleId(this.config.getId());

	            outerLoop:
	            for (Environment environment : environments) {
	                List<PublishingEndPoint> endpoints = new ArrayList<>();

	                //Filter Endpoints list and push only to those that are enabled and ARE static (S3 at the moment)
	                for(PublishingEndPoint ep : this.publisherEndPointAPI.findSendingEndPointsByEnvironment(environment.getId())) {
	                    if(ep.isEnabled() && getProtocols().contains(ep.getProtocol())) {
	                        endpoints.add(ep);
	                    }
	                }

	                for (PublishingEndPoint endpoint : endpoints) {
	                    Properties props = getEndPointProperties(endpoint);

	                    String tokenProp = props.getProperty(DOTCMS_PUSH_AWS_S3_TOKEN);
	                    String secretProp = props.getProperty(DOTCMS_PUSH_AWS_S3_SECRET);
	                    String bucketIDProp = props.getProperty(DOTCMS_PUSH_AWS_S3_BUCKET_ID);
                        String endPointProp = props.getProperty(DOTCMS_PUSH_AWS_S3_ENDPOINT);
                        String bucketRegion = props.getProperty(DOTCMS_PUSH_AWS_S3_BUCKET_REGION);

						AWSS3EndPointPublisher endPointPublisher = getAWSS3EndPointPublisher(tokenProp,
                                secretProp, endPointProp, bucketRegion);

	                    Object oldHost = config.get(CURRENT_HOST);
	                    Object oldLanguage = config.get(CURRENT_LANGUAGE);
	                    Object oldBucketId = config.get(DOTCMS_PUSH_AWS_S3_BUCKET_ID);
	                    try {
	                        config.put(CURRENT_HOST, host);
	                        config.put(CURRENT_LANGUAGE, Long.toString(languageId));
	                        config.put(DOTCMS_PUSH_AWS_S3_BUCKET_ID, bucketIDProp);

	                        final String bucketName = getBucketName(this.config);

	                        if (endPointPublisher.exists(bucketName)) {
	                        	result = true;

	                        	break outerLoop;
	                        }
	                    } finally {
	                        config.put(CURRENT_HOST, oldHost);
	                        config.put(CURRENT_LANGUAGE, oldLanguage);                    	
	                        config.put(DOTCMS_PUSH_AWS_S3_BUCKET_ID, oldBucketId);

	                        endPointPublisher.shutdownTransferManager();
	                    }
	                }
	            }
            }
    	} catch (Exception e){
            Logger.error(this.getClass(), e.getMessage(), e);
    	}

    	shouldForcePushCache.put(cacheKey, result);
    	return result;
    }

    @Override
    public PublisherConfig process(final PublishStatus status) throws DotPublishingException {
        this.checkLicense();
        PublishAuditHistory currentStatusHistory = null;
        try {
        	/*
        	 * Inhibited bundle compression for static-publishing scenario
        	 * Performance enhancement due to https://github.com/dotCMS/core/issues/12291
        	 */
        	if (Config.getBooleanProperty("STATIC_PUBLISHING_GENERATE_TAR_GZ", false)) {
				// Compressing the bundle
				File bundleToCompress = BundlerUtil.getBundleRoot(config);
				ArrayList<File> list = new ArrayList<>(1);
				list.add(bundleToCompress);
				File bundle = new File(bundleToCompress + File.separator + ".." + File.separator + config.getId() + ".tar.gz");
				PushUtils.compressFiles(list, bundle, bundleToCompress.getAbsolutePath());
        	}

        	List<Environment> environments = this.environmentAPI.findEnvironmentsByBundleId(this.config.getId());
            //Updating audit table
            currentStatusHistory = this.publishAuditAPI.getPublishAuditStatus(config.getId()).getStatusPojo();
            Map<String, Map<String, EndpointDetail>> endpointsMap = currentStatusHistory.getEndpointsMap();
            // If not empty, don't overwrite publish history already set via the PublisherQueueJob
            boolean isHistoryEmpty = endpointsMap.size() == 0;
            currentStatusHistory.setPublishStart(new Date());
            this.publishAuditAPI.updatePublishAuditStatus(config.getId(), PublishAuditStatus.Status.SENDING_TO_ENDPOINTS, currentStatusHistory);
            //Increment numTries
            currentStatusHistory.addNumTries();
            int failedEnvironmentCounter = 0;

            for (Environment environment : environments) {
                List<PublishingEndPoint> allEndpoints = this.publisherEndPointAPI.findSendingEndPointsByEnvironment(environment.getId());
                List<PublishingEndPoint> endpoints = new ArrayList<>();

                //Filter Endpoints list and push only to those that are enabled and ARE static (S3 at the moment)
                for(PublishingEndPoint ep : allEndpoints) {
                    if(ep.isEnabled() && getProtocols().contains(ep.getProtocol())) {
                        endpoints.add(ep);
                    }
                }

                boolean failedEnvironment = false;

                if(!environment.getPushToAll()) {
                    Collections.shuffle(endpoints);
                    if(!endpoints.isEmpty())
                        endpoints = endpoints.subList(0, 1);
                }

                for (PublishingEndPoint endpoint : endpoints) {

                    //For logging purpose
                    ThreadContext.put(ENDPOINT_NAME, ENDPOINT_NAME + "=" + endpoint.getServerName());

                    Properties props = getEndPointProperties(endpoint);

                    String tokenProp = props.getProperty(DOTCMS_PUSH_AWS_S3_TOKEN);
                    String secretProp = props.getProperty(DOTCMS_PUSH_AWS_S3_SECRET);
                    String bucketIDProp = props.getProperty(DOTCMS_PUSH_AWS_S3_BUCKET_ID);
                    String bucketPrefixProp = props.getProperty(DOTCMS_PUSH_AWS_S3_BUCKET_ROOT_PREFIX);
                    String bucketRegion = props.getProperty(DOTCMS_PUSH_AWS_S3_BUCKET_REGION);
                    String bucketValidationName = props.getProperty(DOTCMS_PUSH_AWS_S3_BUCKET_VALIDATION_NAME);
                    String wsEndpoint = props.getProperty(DOTCMS_PUSH_AWS_S3_ENDPOINT);

                    //For each endpoint, we reset the bucket list name
                    config.put(CREATED_BUCKETS, new HashSet<String>());

                    config.put(DOTCMS_PUSH_AWS_S3_BUCKET_ID, bucketIDProp);
                    if (UtilMethods.isSet(bucketPrefixProp)){
                        config.put(DOTCMS_PUSH_AWS_S3_BUCKET_ROOT_PREFIX, bucketPrefixProp);
                    } else {
                        config.remove(DOTCMS_PUSH_AWS_S3_BUCKET_ROOT_PREFIX);
                    }

                    if (!UtilMethods.isSet(bucketRegion)){
                        bucketRegion = DOTCMS_PUSH_AWS_S3_BUCKET_REGION_DEFAULT;
                    }

					AWSS3EndPointPublisher endPointPublisher = getAWSS3EndPointPublisher(tokenProp,
                            secretProp, wsEndpoint, bucketRegion);
                    EndpointDetail detail = new EndpointDetail();

                    try {
                        endPointPublisher.checkConnectSuccessfully(bucketValidationName);
                    } catch (final EndPointPublisherConnectionException e) {
                        String error = updateStatusFailedToSend(currentStatusHistory, environment, endpoint, detail);
                        failedEnvironment |= true;
                        Logger.error(this.getClass(), error);
                    }

                    try {
                        PushPublishLogger.log(this.getClass(), "Status Update: Bundle push starting");

                        boolean amIPublishing = PublisherConfig.Operation.PUBLISH.equals(config.getOperation());

                        //Getting the host name, then the languages under the bundle.
                        File bundleRoot = BundlerUtil.getBundleRoot(this.config);

                        //For logging purpose
                        ThreadContext.put(BUNDLE_ID, BUNDLE_ID + "=" + bundleRoot.getName());

                        PushPublishLogger.log(this.getClass(), "Status Update: Pushing bundle");
                        currentStatusHistory.setPublishStart(new Date());
                        File liveFolder = new File(bundleRoot.getAbsolutePath() + LIVE_FOLDER);

                        if ( !liveFolder.exists() ) {
                            Logger.warn(this.getClass(), "Bundle is EMPTY");
                            PushPublishLogger.log(this.getClass(), "Bundle is EMPTY");
                        } else {
                            //For each host.
                            for (File hostFolder : liveFolder.listFiles(FileUtil.getOnlyFolderFileFilter())) {
                                Host host = getHostFromFilePath(hostFolder);
                                config.put(CURRENT_HOST, host);

                                final TreeSet<LanguageFolder> languagesFolders = getLanguageFolders(
                                        hostFolder);
                                Logger.info(this.getClass(), String.format("Pushed languages: %s", languagesFolders.stream().map(lang -> lang.getLanguage()).collect(Collectors.toList())));
                                //For each language.
                                for (LanguageFolder languageFolder : languagesFolders) {
                                    //For each file under i.e. /live/demo.dotcms.com/1/
                                    Language language = languageFolder.getLanguage();
                                    config.put(CURRENT_LANGUAGE, Long.toString(language.getId()));

                                    final String bucketName = getBucketName(this.config);
                                    final String bucketPrefix = getBucketPrefix(bucketPrefixProp, this.config);
                                    final StaticTarget staticTarget =
                                            new StaticTarget(endpoint.getId(), endPointPublisher, bucketName, bucketPrefix);

                                    /* Creates a bucket only if it does not exist.
                                       In order to avoid aws stale reads, we verify against out set of buckets names
                                       if it has not been created yet. In case the bucket name does not exist in the set,
                                       a new bucket is created and its name is saved in the set*/
                                    if (!((Set<String>)config.get(CREATED_BUCKETS)).contains(bucketName)){
                                        endPointPublisher.createBucket(bucketName, bucketRegion);
                                        ((Set<String>)config.get(CREATED_BUCKETS)).add(bucketName);
                                    }

                                    Collection<File> listFiles = amIPublishing ? Arrays.asList(languageFolder.getLanguageFolder().listFiles())
                                        : FileUtils.listFiles(languageFolder.getLanguageFolder(), TrueFileFilter.INSTANCE, TrueFileFilter.INSTANCE);

                                    for (File file : listFiles) {
                                        String filePath = file.getAbsolutePath().replace(bundleRoot.getAbsolutePath()+ LIVE_FOLDER, "");

                                        //Always remove the /hostName/ i.e. /demo.dotcms.com/
                                        filePath = filePath.substring(filePath.indexOf(File.separator, filePath.indexOf(File.separator)+1));

                                        //Always remove the /languageId/ i.e. /1/
                                        filePath = filePath.substring(filePath.indexOf(File.separator, filePath.indexOf(File.separator)+1));

                                        try {
                                            if (amIPublishing) {
                                                endPointPublisher.pushBundleToEndpoint(bucketName, bucketRegion, bucketPrefix, filePath, file);
                                                publishVanityAliases(new VanityRequest(staticTarget, host, language, bundleRoot, file));
                                            } else {
                                                endPointPublisher.deleteFilesFromEndpoint(bucketName, bucketPrefix, filePath);
                                                unpublishVanityAliases(new VanityRequest(staticTarget, host, language, bundleRoot, file));
                                            }
                                        } catch(DotPublishingException e) {
                                            String error = updateStatusFailedToSend(currentStatusHistory, environment, endpoint, detail);
                                            failedEnvironment |= true;
                                            Logger.error(this.getClass(), error, e);
                                        }
                                    }
                                }
                            }
                        }


                    } catch(Exception e) {
                        // if the bundle can't be sent after the total num of tries, delete the pushed assets for this bundle
                        if(currentStatusHistory.getNumTries() >= PublisherQueueJob.MAX_NUM_TRIES) {
                            this.pushedAssetsAPI.deletePushedAssets(config.getId(), environment.getId());
                        }
                        detail.setStatus(PublishAuditStatus.Status.FAILED_TO_PUBLISH.getCode());
                        String error = 	"An error occurred for the endpoint "+ endpoint.getId() + " with address "+ endpoint.getAddress() + ".  Error: " + e.getMessage();
                        detail.setInfo(error);
                        failedEnvironment |= true;

                        Logger.error(this.getClass(), error, e);
                        PushPublishLogger.log(this.getClass(), "Status Update: Failed to publish bundle");
                    } finally {
                        endPointPublisher.shutdownTransferManager();
                        ThreadContext.remove(BUNDLE_ID);
                        ThreadContext.remove(ENDPOINT_NAME);
                    }

                    if (detail.getStatus()==0){
                        detail.setStatus(PublishAuditStatus.Status.SUCCESS.getCode());
                        detail.setInfo("Endpoint " + endpoint.getId() + " published successfully");
                        currentStatusHistory.setPublishEnd(new Date());
                    }

                    if (isHistoryEmpty || failedEnvironment) {
                        currentStatusHistory.addOrUpdateEndpoint(environment.getId(), endpoint.getId(), detail);
                    }
                }
                if(failedEnvironment) {
                    failedEnvironmentCounter++;
                }
            }

            if(failedEnvironmentCounter==0) {
                //Updating Audit table
                PushPublishLogger.log(this.getClass(), "Status Update: Bundle sent");
                this.publishAuditAPI.updatePublishAuditStatus(config.getId(), PublishAuditStatus.Status.BUNDLE_SENT_SUCCESSFULLY, currentStatusHistory);
            } else {
                if(failedEnvironmentCounter == environments.size()) {
                    this.publishAuditAPI.updatePublishAuditStatus(config.getId(), PublishAuditStatus.Status.FAILED_TO_SEND_TO_ALL_GROUPS, currentStatusHistory);
                } else {
                    this.publishAuditAPI.updatePublishAuditStatus(config.getId(), PublishAuditStatus.Status.FAILED_TO_SEND_TO_SOME_GROUPS, currentStatusHistory);
                }
            }

        } catch (Exception e) {
            try {
                PushPublishLogger.log(this.getClass(), "Status Update: Failed to publish");
                this.publishAuditAPI.updatePublishAuditStatus(config.getId(), PublishAuditStatus.Status.FAILED_TO_PUBLISH, currentStatusHistory);
            } catch (DotPublisherException e1) {
                throw new DotPublishingException(e.getMessage(),e);
            }

            Logger.error(this.getClass(), e.getMessage(), e);
            throw new DotPublishingException(e.getMessage(),e);
        }

        return config;
    } // process.

    /**
     * Holds the static endpoint data needed to manage vanity URLs during
     * publishing and unpublishing.
     *
     * @param endpointId endpoint identifier.
     * @param publisher facade to S3.
     * @param bucketName target bucket.
     * @param bucketPrefix configured bucket prefix.
     */
    private static final class StaticTarget {

        private final String endpointId;
        private final AWSS3EndPointPublisher publisher;
        private final String bucketName;
        private final String bucketPrefix;

        /**
         * Creates a new container for the endpoint static data.
         *
         * @param endpointId endpoint identifier.
         * @param publisher facade to S3.
         * @param bucketName target bucket.
         * @param bucketPrefix configured bucket prefix.
         */
        private StaticTarget(
                final String endpointId,
                final AWSS3EndPointPublisher publisher,
                final String bucketName,
                final String bucketPrefix) {
            this.endpointId = endpointId;
            this.publisher = publisher;
            this.bucketName = bucketName;
            this.bucketPrefix = bucketPrefix;
        }

        /**
         * Returns the endpoint identifier.
         *
         * @return endpoint identifier.
         */
        private String endpointId() {
            return endpointId;
        }

        /**
         * Returns the S3 facade.
         *
         * @return S3 endpoint publisher.
         */
        private AWSS3EndPointPublisher publisher() {
            return publisher;
        }

        /**
         * Returns the target bucket.
         *
         * @return bucket name.
         */
        private String bucketName() {
            return bucketName;
        }

        /**
         * Returns the configured bucket prefix.
         *
         * @return bucket prefix.
         */
        private String bucketPrefix() {
            return bucketPrefix;
        }
    }

    /**
     * Collects the context required to process vanity URLs for a specific
     * static file.
     *
     * @param target active S3 destination.
     * @param host host of the static file.
     * @param language language of the static file.
     * @param bundleRoot static bundle root.
     * @param file file or directory being processed.
     */
    private static final class VanityRequest {

        private final StaticTarget target;
        private final Host host;
        private final Language language;
        private final File bundleRoot;
        private final File file;

        /**
         * Creates a new working context for vanity URLs.
         *
         * @param target active S3 destination.
         * @param host host of the static file.
         * @param language language of the static file.
         * @param bundleRoot static bundle root.
         * @param file file or directory being processed.
         */
        private VanityRequest(
                final StaticTarget target,
                final Host host,
                final Language language,
                final File bundleRoot,
                final File file) {
            this.target = target;
            this.host = host;
            this.language = language;
            this.bundleRoot = bundleRoot;
            this.file = file;
        }

        /**
         * Returns the active S3 destination.
         *
         * @return current endpoint context.
         */
        private StaticTarget target() {
            return target;
        }

        /**
         * Returns the host of the static file.
         *
         * @return host of the current file.
         */
        private Host host() {
            return host;
        }

        /**
         * Returns the language of the static file.
         *
         * @return language of the current file.
         */
        private Language language() {
            return language;
        }

        /**
         * Returns the static bundle root.
         *
         * @return bundle root directory.
         */
        private File bundleRoot() {
            return bundleRoot;
        }

        /**
         * Returns the file or directory currently being processed.
         *
         * @return bundle file or directory.
         */
        private File file() {
            return file;
        }
    }

    /**
     * Publishes all vanity aliases compatible with the current static file
     * and refreshes the persisted mapping.
     *
     * @param request current static file context.
     * @throws DotPublishingException when alias publishing fails.
     */
    private void publishVanityAliases(final VanityRequest request) throws DotPublishingException {
        if (!isS3VanityAliasEnabled()) {
            return;
        }

        for (final File staticFile : getFilesForVanityProcessing(request.file())) {
            publishVanityAliasesForFile(request, staticFile);
        }
    }

    /**
     * Unpublishes the persisted vanity aliases for the current static file
     * and removes their mappings when successful.
     *
     * @param request current static file context.
     * @throws DotPublishingException when alias unpublishing fails.
     */
    private void unpublishVanityAliases(final VanityRequest request) throws DotPublishingException {
        if (!isS3VanityAliasEnabled() || request.file().isDirectory()) {
            return;
        }

        final String filePath = toStaticPath(request.bundleRoot(), request.file());
        final S3VanityAliasLookup lookup = toLookup(request, filePath);

        try {
            final List<S3VanityAliasMap> mappings = this.s3VanityAliasMapRepository.findMappings(lookup);
            if (mappings.isEmpty()) {
                return;
            }

            for (final S3VanityAliasMap mapping : mappings) {
                request.target().publisher().deleteFilesFromEndpoint(
                        request.target().bucketName(),
                        request.target().bucketPrefix(),
                        mapping.vanityPath());
            }

            this.s3VanityAliasMapRepository.deleteMappings(lookup);
        } catch (final Exception e) {
            throw new DotPublishingException(e.getMessage(), e);
        }
    }

    /**
     * Publishes vanity aliases for a single static file already materialized
     * in the bundle.
     *
     * @param request current static file context.
     * @param staticFile physical file to duplicate across vanity aliases.
     * @throws DotPublishingException when alias publishing fails.
     */
    private void publishVanityAliasesForFile(final VanityRequest request, final File staticFile)
            throws DotPublishingException {

        if (!staticFile.isFile()) {
            return;
        }

        final String filePath = toStaticPath(request.bundleRoot(), staticFile);
        final S3VanityAliasLookup lookup = toLookup(request, filePath);
        final List<S3VanityAliasMap> mappings = resolveAliasMappings(lookup, request.host(), request.language());
        final List<S3VanityAliasMap> currentMappings = findCurrentMappings(lookup);
        final List<S3VanityAliasMap> obsoleteMappings = findObsoleteMappings(currentMappings, mappings);

        final List<S3VanityAliasMap> publishedMappings = new ArrayList<>();
        final List<S3VanityAliasMap> deletedObsoleteMappings = new ArrayList<>();
        DotPublishingException publishException = null;

        for (final S3VanityAliasMap mapping : mappings) {
            try {
                request.target().publisher().pushFileToEndpoint(
                        request.target().bucketName(),
                        request.target().bucketPrefix(),
                        mapping.vanityPath(),
                        staticFile);
                publishedMappings.add(mapping);
            } catch (final DotPublishingException e) {
                if (publishException == null) {
                    publishException = e;
                }
                Logger.error(this.getClass(),
                        String.format("Unable to publish S3 vanity alias '%s' for canonical path '%s'",
                                mapping.vanityPath(), mapping.canonicalPath()),
                        e);
            }
        }

        if (publishException != null) {
            rollbackPublishedVanityAliases(request, publishedMappings);
            throw publishException;
        }

        try {
            deleteObsoleteVanityAliases(request, obsoleteMappings, deletedObsoleteMappings);
            replaceAliasMappings(lookup, mappings);
        } catch (final DotPublishingException e) {
            rollbackPublishedVanityAliases(request, publishedMappings);
            restoreDeletedVanityAliases(request, deletedObsoleteMappings, staticFile);
            throw e;
        }
    }

    /**
     * Resolves the vanity mappings applicable to a canonical static path.
     *
     * @param lookup logical key for the canonical static file.
     * @param host host of the static file.
     * @param language language of the static file.
     * @return vanity mappings compatible with static S3 publishing.
     */
    private List<S3VanityAliasMap> resolveAliasMappings(
            final S3VanityAliasLookup lookup,
            final Host host,
            final Language language) {

        final List<CachedVanityUrl> vanityUrls = this.vanityUrlAPI.findByForward(
                host,
                language,
                lookup.canonicalPath(),
                HttpServletResponse.SC_OK,
                true);

        final List<S3VanityAliasMap> mappings = S3VanityAliasSupport.toAliasMaps(lookup, vanityUrls);
        logUnsupportedVanityUrls(lookup, vanityUrls);
        return mappings;
    }

    /**
     * Logs Vanity URLs that were found but are not compatible with static S3
     * publishing.
     *
     * @param lookup logical key for the canonical static file.
     * @param vanityUrls vanity URL candidates.
     */
    private void logUnsupportedVanityUrls(
            final S3VanityAliasLookup lookup,
            final List<CachedVanityUrl> vanityUrls) {

        vanityUrls.stream()
                .filter(Objects::nonNull)
                .map(vanityUrl -> vanityUrl.url)
                .filter(vanityPath -> !S3VanityAliasSupport.isSupportedVanityPath(vanityPath))
                .forEach(vanityPath -> Logger.warn(this.getClass(),
                        String.format("Skipping unsupported S3 vanity alias '%s' for canonical path '%s'",
                                vanityPath, lookup.canonicalPath())));
    }

    /**
     * Replaces the persisted mappings for a canonical static path.
     *
     * @param lookup logical key for the canonical static file.
     * @param mappings mappings to save.
     * @throws DotPublishingException when persistence fails.
     */
    private void replaceAliasMappings(
            final S3VanityAliasLookup lookup,
            final List<S3VanityAliasMap> mappings) throws DotPublishingException {
        try {
            this.s3VanityAliasMapRepository.replaceMappings(lookup, mappings);
        } catch (final DotDataException e) {
            throw new DotPublishingException(e.getMessage(), e);
        }
    }

    /**
     * Returns the current persisted mappings for a canonical static path.
     *
     * @param lookup logical key for the canonical static file.
     * @return mappings currently stored for the lookup.
     * @throws DotPublishingException when the lookup fails.
     */
    private List<S3VanityAliasMap> findCurrentMappings(final S3VanityAliasLookup lookup)
            throws DotPublishingException {
        try {
            return this.s3VanityAliasMapRepository.findMappings(lookup);
        } catch (final DotDataException e) {
            throw new DotPublishingException(e.getMessage(), e);
        }
    }

    /**
     * Resolves previously persisted aliases that are no longer part of the
     * desired alias set.
     *
     * @param currentMappings mappings currently persisted.
     * @param desiredMappings mappings resolved for the current publish operation.
     * @return stale mappings that must be removed from S3.
     */
    private List<S3VanityAliasMap> findObsoleteMappings(
            final List<S3VanityAliasMap> currentMappings,
            final List<S3VanityAliasMap> desiredMappings) {

        final Set<String> desiredPaths = desiredMappings.stream()
                .map(S3VanityAliasMap::vanityPath)
                .collect(Collectors.toSet());

        return currentMappings.stream()
                .filter(mapping -> !desiredPaths.contains(mapping.vanityPath()))
                .collect(Collectors.toList());
    }

    /**
     * Deletes stale aliases and records the ones already removed so they can be
     * restored if the batch fails before the mapping update completes.
     *
     * @param request current static file context.
     * @param obsoleteMappings aliases that should no longer exist.
     * @param deletedObsoleteMappings collector for aliases deleted successfully.
     * @throws DotPublishingException when deletion fails.
     */
    private void deleteObsoleteVanityAliases(
            final VanityRequest request,
            final List<S3VanityAliasMap> obsoleteMappings,
            final List<S3VanityAliasMap> deletedObsoleteMappings) throws DotPublishingException {

        for (final S3VanityAliasMap mapping : obsoleteMappings) {
            request.target().publisher().deleteFilesFromEndpoint(
                    request.target().bucketName(),
                    request.target().bucketPrefix(),
                    mapping.vanityPath());
            deletedObsoleteMappings.add(mapping);
        }
    }

    /**
     * Restores previously deleted obsolete aliases when the publish flow fails
     * before the new mapping state can be persisted.
     *
     * @param request current static file context.
     * @param deletedObsoleteMappings aliases deleted before the failure.
     * @param staticFile physical file used to recreate the aliases.
     */
    private void restoreDeletedVanityAliases(
            final VanityRequest request,
            final List<S3VanityAliasMap> deletedObsoleteMappings,
            final File staticFile) {

        for (final S3VanityAliasMap mapping : deletedObsoleteMappings) {
            try {
                request.target().publisher().pushFileToEndpoint(
                        request.target().bucketName(),
                        request.target().bucketPrefix(),
                        mapping.vanityPath(),
                        staticFile);
            } catch (final DotPublishingException e) {
                Logger.error(this.getClass(),
                        String.format("Unable to restore obsolete S3 vanity alias '%s' for canonical path '%s'",
                                mapping.vanityPath(), mapping.canonicalPath()),
                        e);
            }
        }
    }

    /**
     * Removes vanity aliases already pushed for a file when the batch cannot
     * be completed successfully.
     *
     * @param request current static file context.
     * @param publishedMappings aliases already pushed to S3.
     */
    private void rollbackPublishedVanityAliases(
            final VanityRequest request,
            final List<S3VanityAliasMap> publishedMappings) {

        for (final S3VanityAliasMap mapping : publishedMappings) {
            try {
                request.target().publisher().deleteFilesFromEndpoint(
                        request.target().bucketName(),
                        request.target().bucketPrefix(),
                        mapping.vanityPath());
            } catch (final DotPublishingException e) {
                Logger.error(this.getClass(),
                        String.format("Unable to rollback S3 vanity alias '%s' for canonical path '%s'",
                                mapping.vanityPath(), mapping.canonicalPath()),
                        e);
            }
        }
    }

    /**
     * Translates the physical bundle file into the static path actually used
     * as the S3 logical key.
     *
     * @param bundleRoot static bundle root.
     * @param file physical bundle file.
     * @return static path relative to host and language.
     */
    private String toStaticPath(final File bundleRoot, final File file) {
        String filePath = file.getAbsolutePath().replace(bundleRoot.getAbsolutePath() + LIVE_FOLDER, "");
        filePath = filePath.substring(filePath.indexOf(File.separator, filePath.indexOf(File.separator) + 1));
        return filePath.substring(filePath.indexOf(File.separator, filePath.indexOf(File.separator) + 1));
    }

    /**
     * Returns the leaf files used to manage vanity URLs.
     *
     * @param file file or directory being processed.
     * @return leaf files actually published on S3.
     */
    private List<File> getFilesForVanityProcessing(final File file) {
        if (file.isDirectory()) {
            return new ArrayList<>(FileUtils.listFiles(file, TrueFileFilter.INSTANCE, TrueFileFilter.INSTANCE));
        }

        return List.of(file);
    }

    /**
     * Builds the logical key used to read or write persisted vanity mappings.
     *
     * @param request current static file context.
     * @param filePath canonical static file path.
     * @return logical key for the mapping table.
     */
    private S3VanityAliasLookup toLookup(final VanityRequest request, final String filePath) {
        return new S3VanityAliasLookup(
                request.target().endpointId(),
                request.host().getIdentifier(),
                request.language().getId(),
                filePath);
    }

    /**
     * Indicates whether the static S3 vanity alias feature is enabled.
     *
     * @return {@code true} when the feature is enabled.
     */
    private boolean isS3VanityAliasEnabled() {
        return S3_VANITY_ALIAS_ENABLED.get();
    }

    @NotNull
    private String updateStatusFailedToSend(PublishAuditHistory currentStatusHistory, Environment environment, PublishingEndPoint endpoint, EndpointDetail detail) throws DotDataException {
        // if the bundle can't be sent after the total num of tries, delete the pushed assets for this bundle
        if(currentStatusHistory.getNumTries() >= PublisherQueueJob.MAX_NUM_TRIES) {
            this.pushedAssetsAPI.deletePushedAssets(config.getId(), environment.getId());
        }
        detail.setStatus(PublishAuditStatus.Status.FAILED_TO_SENT.getCode());
        String error = 	"An error occurred for the endpoint " + endpoint.getId() + " Error: Can't connect to End Point";
        detail.setInfo(error);
        return error;
    }

    /**
	 * Creates and returns an AWSS3EndPointPublisher.
	 * Uses the default AWS credentials provider chain if the tokenProp and/or secretProp are not set.
	 * Uses the provided tokenProp and secretProp if they are both set.
	 *
	 * @param tokenProp - the AWS credentials token key
	 * @param secretProp - the AWS credentials secret key
     * @param endPoint - S3 server to connect
     *
	 * @return - a ready-to-use endpoint publisher
	 */
	private AWSS3EndPointPublisher getAWSS3EndPointPublisher(final String tokenProp,
            final String secretProp, final String endPoint, final String region) {
		AWSS3EndPointPublisher endPointPublisher;
		if (!UtilMethods.isSet(tokenProp) || !UtilMethods.isSet(secretProp)) {
			DefaultAWSCredentialsProviderChain creds = new DefaultAWSCredentialsProviderChain();
			endPointPublisher = new AWSS3EndPointPublisher(creds);
		} else {
			AWSS3Configuration awss3Configuration =
					new AWSS3Configuration.Builder()
                            .accessKey(tokenProp)
                            .secretKey(secretProp)
                            .endPoint(endPoint)
                            .region(region)
                            .build();

			endPointPublisher = new AWSS3EndPointPublisher(awss3Configuration);
		}
		return endPointPublisher;
	}

	/**
	 * 
	 * @param prefix
	 * @param config
	 * @return
	 */
    private String getBucketPrefix(final String prefix, final PublisherConfig config) {
        final Map<String, Object> contextMap = this.getContextMap (prefix, config);
        final String bucketPrefix = StringUtils.interpolate(prefix, contextMap);
        return (null != bucketPrefix && bucketPrefix.trim().length() > 0)?
                bucketPrefix: null;
    } //getBucketPrefix

    /**
     * Figure out the bucket name.
     * @param config {@link PublisherConfig}
     * @return List of String
     */
    protected String getBucketName(final PublisherConfig config) {

        final String bucketID   = (String) config.get(DOTCMS_PUSH_AWS_S3_BUCKET_ID);
        final Map<String, Object> contextMap = this.getContextMap(bucketID, config);
        final String bucketName = StringUtils.interpolate(bucketID, contextMap);

        return (null != bucketName && bucketName.trim().length() > 0) ?
                this.normalizeBucketName(bucketName) : DEFAULT_BUCKET_NAME;

    } // getBucketName.

    /**
     * Normalize the bucket name.
     * @param bucketName String
     * @return String
     */
    protected String normalizeBucketName (final String bucketName) {
        //Check for blank spaces and other special characters.
        final String regexValidation =
            Config.getStringProperty("STATIC_PUSH_BUCKET_NAME_REGEX",
                "[,!:;&?$*\\/\\\\\\[\\]=\\|#_@\\(\\)<>\\s]+");
        String normalizedBucketName = bucketName.replaceAll(regexValidation, "-");
        return normalizedBucketName.toLowerCase();
    } //normalizeBucketName



    @Override
    public List<Class> getBundlers() {
        final List<Class> list = new ArrayList<>();

        list.add(StaticDependencyBundler.class);
        list.add(FileAssetBundler.class);
        list.add(HTMLPageAsContentBundler.class);
        list.add(URLMapBundler.class);
        list.add(BinaryExporterBundler.class);
        list.add(CSSExporterBundler.class);
        list.add(ShortyBundler.class);
        return list;
    } // getBundlers.

    @Override
    public Set<String> getProtocols(){
        Set<String> protocols = new HashSet<>();
        protocols.add(PROTOCOL_AWS_S3);
        return  protocols;
    }

} // E:O:F:AWSS3Publisher.
