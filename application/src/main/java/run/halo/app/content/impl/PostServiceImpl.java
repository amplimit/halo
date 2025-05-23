package run.halo.app.content.impl;

import static run.halo.app.extension.index.query.QueryFactory.in;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.function.UnaryOperator;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Sort;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import run.halo.app.content.AbstractContentService;
import run.halo.app.content.CategoryService;
import run.halo.app.content.ContentRequest;
import run.halo.app.content.ContentWrapper;
import run.halo.app.content.Contributor;
import run.halo.app.content.ListedPost;
import run.halo.app.content.ListedSnapshotDto;
import run.halo.app.content.PostQuery;
import run.halo.app.content.PostRequest;
import run.halo.app.content.PostService;
import run.halo.app.content.Stats;
import run.halo.app.core.counter.CounterService;
import run.halo.app.core.counter.MeterUtils;
import run.halo.app.core.extension.content.Category;
import run.halo.app.core.extension.content.Post;
import run.halo.app.core.extension.content.Snapshot;
import run.halo.app.core.extension.content.Tag;
import run.halo.app.core.user.service.UserService;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ListResult;
import run.halo.app.extension.MetadataOperator;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Ref;
import run.halo.app.infra.messaging.DistributedEvent;
import run.halo.app.infra.messaging.OutboxService;
import run.halo.app.infra.messaging.RedisStreamEventPublisher;
import java.util.Map;
import java.util.HashMap;
import run.halo.app.extension.router.selector.FieldSelector;
import run.halo.app.infra.Condition;
import run.halo.app.infra.ConditionStatus;

/**
 * A default implementation of {@link PostService}.
 *
 * @author guqing
 * @since 2.0.0
 */
@Slf4j
@Component
public class PostServiceImpl extends AbstractContentService implements PostService {
    private final ReactiveExtensionClient client;
    private final CounterService counterService;
    private final UserService userService;
    private final CategoryService categoryService;
    private final RedisStreamEventPublisher eventPublisher;
    private final OutboxService outboxService;

    public PostServiceImpl(ReactiveExtensionClient client, CounterService counterService,
        UserService userService, CategoryService categoryService,
        RedisStreamEventPublisher eventPublisher, OutboxService outboxService) {
        super(client);
        this.client = client;
        this.counterService = counterService;
        this.userService = userService;
        this.categoryService = categoryService;
        this.eventPublisher = eventPublisher;
        this.outboxService = outboxService;
    }

    @Override
    public Mono<ListResult<ListedPost>> listPost(PostQuery query) {
        return buildListOptions(query)
            .flatMap(listOptions ->
                client.listBy(Post.class, listOptions, query.toPageRequest())
            )
            .flatMap(listResult -> Flux.fromStream(listResult.get())
                .map(this::getListedPost)
                .flatMapSequential(Function.identity())
                .collectList()
                .map(listedPosts -> new ListResult<>(listResult.getPage(), listResult.getSize(),
                    listResult.getTotal(), listedPosts)
                )
                .defaultIfEmpty(ListResult.emptyResult())
            );
    }

    Mono<ListOptions> buildListOptions(PostQuery query) {
        var categoryName = query.getCategoryWithChildren();
        if (categoryName == null) {
            return Mono.just(query.toListOptions());
        }
        return categoryService.listChildren(categoryName)
            .collectList()
            .map(categories -> {
                var categoryNames = categories.stream()
                    .map(Category::getMetadata)
                    .map(MetadataOperator::getName)
                    .toList();
                var listOptions = query.toListOptions();
                var newFiledSelector = listOptions.getFieldSelector()
                    .andQuery(in("spec.categories", categoryNames));
                listOptions.setFieldSelector(newFiledSelector);
                return listOptions;
            });
    }

    Mono<Stats> fetchStats(Post post) {
        Assert.notNull(post, "The post must not be null.");
        String name = post.getMetadata().getName();
        return counterService.getByName(MeterUtils.nameOf(Post.class, name))
            .map(counter -> Stats.builder()
                .visit(counter.getVisit())
                .upvote(counter.getUpvote())
                .totalComment(counter.getTotalComment())
                .approvedComment(counter.getApprovedComment())
                .build()
            )
            .defaultIfEmpty(Stats.empty());
    }

    private Mono<ListedPost> getListedPost(Post post) {
        Assert.notNull(post, "The post must not be null.");
        var listedPost = new ListedPost().setPost(post);

        var statsMono = fetchStats(post)
            .doOnNext(listedPost::setStats);

        var tagsMono = listTags(post.getSpec().getTags())
            .collectList()
            .doOnNext(listedPost::setTags);

        var categoriesMono = listCategories(post.getSpec().getCategories())
            .collectList()
            .doOnNext(listedPost::setCategories);

        var contributorsMono = listContributors(post.getStatusOrDefault().getContributors())
            .collectList()
            .doOnNext(listedPost::setContributors);

        var ownerMono = userService.getUserOrGhost(post.getSpec().getOwner())
            .map(user -> {
                Contributor contributor = new Contributor();
                contributor.setName(user.getMetadata().getName());
                contributor.setDisplayName(user.getSpec().getDisplayName());
                contributor.setAvatar(user.getSpec().getAvatar());
                return contributor;
            })
            .doOnNext(listedPost::setOwner);
        return Mono.when(statsMono, tagsMono, categoriesMono, contributorsMono, ownerMono)
            .thenReturn(listedPost);
    }

    private Flux<Tag> listTags(List<String> tagNames) {
        if (tagNames == null) {
            return Flux.empty();
        }
        var listOptions = new ListOptions();
        listOptions.setFieldSelector(FieldSelector.of(in("metadata.name", tagNames)));
        return client.listAll(Tag.class, listOptions, Sort.by("metadata.creationTimestamp"));
    }

    @Override
    public Flux<Category> listCategories(List<String> categoryNames) {
        if (categoryNames == null) {
            return Flux.empty();
        }
        ToIntFunction<Category> comparator =
            category -> categoryNames.indexOf(category.getMetadata().getName());
        var listOptions = new ListOptions();
        listOptions.setFieldSelector(FieldSelector.of(in("metadata.name", categoryNames)));
        return client.listAll(Category.class, listOptions, Sort.unsorted())
            .sort(Comparator.comparingInt(comparator));
    }

    private Flux<Contributor> listContributors(List<String> usernames) {
        if (usernames == null) {
            return Flux.empty();
        }
        return Flux.fromIterable(usernames)
            .flatMapSequential(userService::getUserOrGhost)
            .map(user -> {
                Contributor contributor = new Contributor();
                contributor.setName(user.getMetadata().getName());
                contributor.setDisplayName(user.getSpec().getDisplayName());
                contributor.setAvatar(user.getSpec().getAvatar());
                return contributor;
            });
    }

    @Override
    public Mono<Post> draftPost(PostRequest postRequest) {
        return Mono.defer(
                () -> {
                    var post = postRequest.post();
                    return getContextUsername()
                        .doOnNext(username -> post.getSpec().setOwner(username))
                        .thenReturn(post);
                })
            .flatMap(client::create)
            .flatMap(post -> {
                if (postRequest.content() == null) {
                    return Mono.just(post);
                }
                var contentRequest =
                    new ContentRequest(Ref.of(post), post.getSpec().getHeadSnapshot(),
                        null,
                        postRequest.content().raw(), postRequest.content().content(),
                        postRequest.content().rawType());
                return draftContent(post.getSpec().getBaseSnapshot(), contentRequest)
                    .flatMap(contentWrapper -> waitForPostToDraftConcludingWork(
                        post.getMetadata().getName(),
                        contentWrapper)
                    );
            })
            .retryWhen(Retry.backoff(5, Duration.ofMillis(100))
                .filter(OptimisticLockingFailureException.class::isInstance))
            .flatMap(post -> {
                // Create a distributed event and save to outbox
                DistributedEvent event = DistributedEvent.builder()
                    .type(DistributedEvent.EventType.POST_CREATED)
                    .entityId(post.getMetadata().getName())
                    .entityType("Post")
                    .operation("DRAFT")
                    .build();
                return outboxService.saveEvent(event)
                    .thenReturn(post);
            });
    }

    private Mono<Post> waitForPostToDraftConcludingWork(String postName,
        ContentWrapper contentWrapper) {
        return Mono.defer(() -> client.fetch(Post.class, postName)
                .flatMap(post -> {
                    post.getSpec().setBaseSnapshot(contentWrapper.getSnapshotName());
                    post.getSpec().setHeadSnapshot(contentWrapper.getSnapshotName());
                    if (Objects.equals(true, post.getSpec().getPublish())) {
                        post.getSpec().setReleaseSnapshot(post.getSpec().getHeadSnapshot());
                    }
                    Condition condition = Condition.builder()
                        .type(Post.PostPhase.DRAFT.name())
                        .reason("DraftedSuccessfully")
                        .message("Drafted post successfully.")
                        .status(ConditionStatus.TRUE)
                        .lastTransitionTime(Instant.now())
                        .build();
                    Post.PostStatus status = post.getStatusOrDefault();
                    status.setPhase(Post.PostPhase.DRAFT.name());
                    status.getConditionsOrDefault().addAndEvictFIFO(condition);
                    return client.update(post);
                }))
            .retryWhen(Retry.backoff(5, Duration.ofMillis(100))
                .filter(OptimisticLockingFailureException.class::isInstance));
    }

    @Override
    public Mono<Post> updatePost(PostRequest postRequest) {
        Post post = postRequest.post();
        String headSnapshot = post.getSpec().getHeadSnapshot();
        String releaseSnapshot = post.getSpec().getReleaseSnapshot();
        String baseSnapshot = post.getSpec().getBaseSnapshot();

        if (StringUtils.equals(releaseSnapshot, headSnapshot)) {
            // create new snapshot to update first
            return draftContent(baseSnapshot, postRequest.contentRequest(), headSnapshot)
                .flatMap(contentWrapper -> {
                    post.getSpec().setHeadSnapshot(contentWrapper.getSnapshotName());
                    return client.update(post);
                });
        }
        return updateContent(baseSnapshot, postRequest.contentRequest())
            .flatMap(contentWrapper -> {
                post.getSpec().setHeadSnapshot(contentWrapper.getSnapshotName());
                return client.update(post);
            });
    }

    @Override
    public Mono<Post> updateBy(@NonNull Post post) {
        return client.update(post);
    }

    @Override
    public Mono<ContentWrapper> getHeadContent(String postName) {
        return client.get(Post.class, postName)
            .flatMap(this::getHeadContent);
    }

    @Override
    public Mono<ContentWrapper> getHeadContent(Post post) {
        var headSnapshot = post.getSpec().getHeadSnapshot();
        return getContent(headSnapshot, post.getSpec().getBaseSnapshot());
    }

    @Override
    public Mono<ContentWrapper> getReleaseContent(String postName) {
        return client.get(Post.class, postName)
            .flatMap(this::getReleaseContent);
    }

    @Override
    public Mono<ContentWrapper> getReleaseContent(Post post) {
        var releaseSnapshot = post.getSpec().getReleaseSnapshot();
        return getContent(releaseSnapshot, post.getSpec().getBaseSnapshot());
    }

    @Override
    public Flux<ListedSnapshotDto> listSnapshots(String name) {
        return client.fetch(Post.class, name)
            .flatMapMany(page -> listSnapshotsBy(Ref.of(page)))
            .map(ListedSnapshotDto::from);
    }

    @Override
    public Mono<Post> publish(Post post) {
        var spec = post.getSpec();
        spec.setPublish(true);
        if (spec.getHeadSnapshot() == null) {
            spec.setHeadSnapshot(spec.getBaseSnapshot());
        }
        spec.setReleaseSnapshot(spec.getHeadSnapshot());
        return client.update(post)
            .flatMap(updatedPost -> {
                // Create a distributed event and save to outbox
                DistributedEvent event = DistributedEvent.builder()
                    .type(DistributedEvent.EventType.POST_UPDATED)
                    .entityId(updatedPost.getMetadata().getName())
                    .entityType("Post")
                    .operation("PUBLISH")
                    .build();
                return outboxService.saveEvent(event)
                    .thenReturn(updatedPost);
            });
    }

    @Override
    public Mono<Post> unpublish(Post post) {
        post.getSpec().setPublish(false);
        return client.update(post)
            .flatMap(updatedPost -> {
                // Create a distributed event and save to outbox
                DistributedEvent event = DistributedEvent.builder()
                    .type(DistributedEvent.EventType.POST_UPDATED)
                    .entityId(updatedPost.getMetadata().getName())
                    .entityType("Post")
                    .operation("UNPUBLISH")
                    .build();
                return outboxService.saveEvent(event)
                    .thenReturn(updatedPost);
            });
    }

    @Override
    public Mono<Post> getByUsername(String postName, String username) {
        return client.get(Post.class, postName)
            .filter(post -> post.getSpec() != null)
            .filter(post -> Objects.equals(username, post.getSpec().getOwner()));
    }

    @Override
    public Mono<Post> revertToSpecifiedSnapshot(String postName, String snapshotName) {
        return client.get(Post.class, postName)
            .filter(post -> {
                var head = post.getSpec().getHeadSnapshot();
                return !StringUtils.equals(head, snapshotName);
            })
            .flatMap(post -> {
                var baseSnapshot = post.getSpec().getBaseSnapshot();
                return getContent(snapshotName, baseSnapshot)
                    .map(content -> ContentRequest.builder()
                        .subjectRef(Ref.of(post))
                        .headSnapshotName(post.getSpec().getHeadSnapshot())
                        .content(content.getContent())
                        .raw(content.getRaw())
                        .rawType(content.getRawType())
                        .build()
                    )
                    .flatMap(contentRequest -> draftContent(baseSnapshot, contentRequest))
                    .flatMap(content -> {
                        post.getSpec().setHeadSnapshot(content.getSnapshotName());
                        return publishPostWithRetry(post);
                    });
            });
    }

    @Override
    public Mono<ContentWrapper> deleteContent(String postName, String snapshotName) {
        return client.get(Post.class, postName)
            .flatMap(post -> {
                var headSnapshotName = post.getSpec().getHeadSnapshot();
                if (StringUtils.equals(headSnapshotName, snapshotName)) {
                    return updatePostWithRetry(post, record -> {
                        // update head to release
                        record.getSpec().setHeadSnapshot(record.getSpec().getReleaseSnapshot());
                        return record;
                    });
                }
                return Mono.just(post);
            })
            .flatMap(post -> {
                var baseSnapshotName = post.getSpec().getBaseSnapshot();
                var releaseSnapshotName = post.getSpec().getReleaseSnapshot();
                if (StringUtils.equals(releaseSnapshotName, snapshotName)) {
                    return Mono.error(new ServerWebInputException(
                        "The snapshot to delete is the release snapshot, please"
                            + " revert to another snapshot first."));
                }
                if (StringUtils.equals(baseSnapshotName, snapshotName)) {
                    return Mono.error(
                        new ServerWebInputException("The first snapshot cannot be deleted."));
                }
                return client.fetch(Snapshot.class, snapshotName)
                    .flatMap(client::delete)
                    .flatMap(deleted -> restoredContent(baseSnapshotName, deleted));
            });
    }

    @Override
    public Mono<Post> recycleBy(String postName, String username) {
        return getByUsername(postName, username)
            .flatMap(post -> updatePostWithRetry(post, record -> {
                record.getSpec().setDeleted(true);
                return record;
            }))
            .flatMap(deletedPost -> {
                // Create a distributed event and save to outbox
                DistributedEvent event = DistributedEvent.builder()
                    .type(DistributedEvent.EventType.POST_DELETED)
                    .entityId(deletedPost.getMetadata().getName())
                    .entityType("Post")
                    .operation("RECYCLE")
                    .build();
                return outboxService.saveEvent(event)
                    .thenReturn(deletedPost);
            });
    }

    private Mono<Post> updatePostWithRetry(Post post, UnaryOperator<Post> func) {
        return client.update(func.apply(post))
            .onErrorResume(OptimisticLockingFailureException.class,
                e -> Mono.defer(() -> client.get(Post.class, post.getMetadata().getName())
                        .map(func)
                        .flatMap(client::update)
                    )
                    .retryWhen(Retry.backoff(8, Duration.ofMillis(100))
                        .filter(OptimisticLockingFailureException.class::isInstance))
            );
    }

    Mono<Post> publishPostWithRetry(Post post) {
        return publish(post)
            .onErrorResume(OptimisticLockingFailureException.class,
                e -> Mono.defer(() -> client.get(Post.class, post.getMetadata().getName())
                        .flatMap(this::publish))
                    .retryWhen(Retry.backoff(8, Duration.ofMillis(100))
                        .filter(OptimisticLockingFailureException.class::isInstance))
            );
    }
}
