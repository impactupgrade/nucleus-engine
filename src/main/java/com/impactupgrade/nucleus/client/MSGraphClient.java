/*
 * Copyright (c) 2024 3River Development LLC, DBA Impact Upgrade. All rights reserved.
 */

package com.impactupgrade.nucleus.client;

import com.azure.identity.ClientSecretCredential;
import com.azure.identity.ClientSecretCredentialBuilder;
import com.impactupgrade.nucleus.environment.Environment;
import com.microsoft.graph.authentication.TokenCredentialAuthProvider;
import com.microsoft.graph.core.ClientException;
import com.microsoft.graph.models.DriveItem;
import com.microsoft.graph.models.Site;
import com.microsoft.graph.requests.DriveItemCollectionPage;
import com.microsoft.graph.requests.DriveItemContentStreamRequest;
import com.microsoft.graph.requests.GraphServiceClient;

import java.io.InputStream;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;

public class MSGraphClient {

    private static final String DEFAULT_SCOPE = "https://graph.microsoft.com/.default";

    protected final Environment env;
    protected final ClientSecretCredential clientSecretCredential;
    protected final TokenCredentialAuthProvider tokenCredentialAuthProvider;
    protected final GraphServiceClient graphClient;

    public MSGraphClient(Environment env) {
        this.env = env;
        List<String> scopes = List.of(DEFAULT_SCOPE);
        this.clientSecretCredential = new ClientSecretCredentialBuilder()
                .clientId(env.getConfig().sharePoint.clientId)
                .clientSecret(env.getConfig().sharePoint.clientSecret)
                .tenantId(env.getConfig().sharePoint.tenantId)
                .build();
        this.tokenCredentialAuthProvider = new TokenCredentialAuthProvider(scopes, clientSecretCredential);

        this.graphClient = GraphServiceClient.builder()
                .authenticationProvider(tokenCredentialAuthProvider)
                .buildClient();
    }

    public GraphServiceClient getClient() {
        return this.graphClient;
    }

    // GET /sites/{rootSiteHostName}:/sites/{subSiteName}
    public Site getSubSite(String rootSiteHostname, String subSiteName) {
        String subSiteUrl = rootSiteHostname + ":/sites/" + subSiteName;
        return graphClient.sites(subSiteUrl).buildRequest().get();
    }

    // GET /sites/{siteId}/drive/items
    public List<DriveItem> getSiteDriveItems(String siteId) {
        DriveItemCollectionPage driveItemsPage = graphClient.sites(siteId).drive().root().children().buildRequest().get();
        List<DriveItem> driveItems = new LinkedList<>();
        driveItems.addAll(getDriveItems(driveItemsPage));

        while (Objects.nonNull(driveItemsPage.getNextPage())) {
            driveItemsPage = driveItemsPage.getNextPage().buildRequest().get();
            driveItems.addAll(getDriveItems(driveItemsPage));
        }
        return driveItems;
    }

    // GET /users/{userId}/drive/items
    public List<DriveItem> getDriveItems(String userId) {
        DriveItemCollectionPage driveItemsPage = graphClient.users(userId).drive().root().children().buildRequest().get();
        List<DriveItem> driveItems = new LinkedList<>();
        driveItems.addAll(getDriveItems(driveItemsPage));

        while (Objects.nonNull(driveItemsPage.getNextPage())) {
            driveItemsPage = driveItemsPage.getNextPage().buildRequest().get();
            driveItems.addAll(getDriveItems(driveItemsPage));
        }
        return driveItems;
    }

    private List<DriveItem> getDriveItems(DriveItemCollectionPage driveItemCollectionPage) {
        return driveItemCollectionPage.getCurrentPage().stream().toList();
    }

    // GET /sites/{siteId}/drive/root:/path/to/file
    public InputStream getSiteDriveItemByPath(String siteId, String pathToFile) {
        return getInputStream(graphClient.sites(siteId).drive().root().itemWithPath(pathToFile).content().buildRequest());
    }

    //GET /drives/{drive-id}/items/{item-id}/content
    public InputStream getDriveItemById(String userId, String driveItemId) {
        return getInputStream(graphClient.users(userId).drive().items().byId(driveItemId).content().buildRequest());
    }

    //GET /drives/{drive-id}/root:/{item-path}
    public InputStream getDriveItemByPath(String driveId, String itemPath) {
        return getInputStream(graphClient.drives(driveId).root().itemWithPath(itemPath).content().buildRequest());
    }

    // GET /groups/{group-id}/drive/items/{item-id}/content
    public InputStream getDriveItemByGroupIdAndItemId(String groupId, String itemId) {
        return getInputStream(graphClient.groups(groupId).drive().items(itemId).content().buildRequest());
    }

    // GET /shares/{shareIdOrEncodedSharingUrl}/driveItem/content
    public InputStream getDriveItemBySharedId(String sharedId) {
        return getInputStream(graphClient.shares(sharedId).driveItem().content().buildRequest());
    }

    // GET /sites/{siteId}/drive/items/{item-id}/content
    public InputStream getDriveItemBySiteIdAndItemId(String siteId, String itemId) {
        return getInputStream(graphClient.sites(siteId).drive().items(itemId).content().buildRequest());
    }

    private InputStream getInputStream(DriveItemContentStreamRequest request) {
        try {
            return request.get();
        } catch (ClientException e) {
            env.logJobError("Failed to execute request!", e.getMessage());
        }
        return null;
    }

}
