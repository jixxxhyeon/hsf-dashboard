-- HSF Dashboard v2 초기 스키마
-- 핵심: 집계 결과가 아니라 커밋 '원본'을 저장한다. 집계는 조회 시점에 SQL로.

-- 프로젝트: 저장소 묶음 단위 (histudy-fe + histudy-be = 프로젝트 1개)
CREATE TABLE project (
    id          BIGSERIAL PRIMARY KEY,
    slug        VARCHAR(64)  NOT NULL UNIQUE,
    name        VARCHAR(128) NOT NULL,
    description TEXT,
    category    VARCHAR(32),
    status      VARCHAR(16)  NOT NULL DEFAULT 'active',
    started_on  DATE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 저장소: 실제 GitHub 리포지토리
CREATE TABLE repository (
    id             BIGSERIAL PRIMARY KEY,
    project_id     BIGINT       NOT NULL REFERENCES project(id),
    owner          VARCHAR(64)  NOT NULL,
    name           VARCHAR(128) NOT NULL,
    default_branch VARCHAR(64)  NOT NULL DEFAULT 'main',
    last_synced_at TIMESTAMPTZ,
    UNIQUE (owner, name)
);

-- 회원: 사람. GitHub API로는 알 수 없는 정보(학번/학과)를 담는다.
CREATE TABLE member (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(64),
    student_id VARCHAR(16) UNIQUE,
    department VARCHAR(64),
    role       VARCHAR(32) NOT NULL DEFAULT 'contributor',
    joined_at  DATE
);

-- GitHub 계정: 한 사람이 계정 2개일 수 있으므로 member 와 N:1
CREATE TABLE github_account (
    id         BIGSERIAL PRIMARY KEY,
    login      VARCHAR(64) NOT NULL UNIQUE,
    github_id  BIGINT UNIQUE,              -- login 은 바뀌지만 이건 안 바뀜
    avatar_url TEXT,                       -- 없으면 github_id 로 만들어 쓴다
    member_id  BIGINT REFERENCES member(id),
    is_bot     BOOLEAN NOT NULL DEFAULT FALSE
);

-- 커밋 이메일 → 계정 매칭 보조. 학번 추출에도 쓴다.
CREATE TABLE commit_email (
    email      VARCHAR(255) PRIMARY KEY,
    account_id BIGINT REFERENCES github_account(id)
);

-- 커밋 원본. 이 프로젝트의 핵심 테이블.
-- 이름을 commit 대신 commit_log 로 둔 이유: commit 은 SQL 키워드라
-- 쿼리/ORM 에서 따옴표를 계속 붙여야 하는 상황이 생긴다.
CREATE TABLE commit_log (
    id            BIGSERIAL PRIMARY KEY,
    repository_id BIGINT      NOT NULL REFERENCES repository(id),
    sha           CHAR(40)    NOT NULL,
    author_id     BIGINT      REFERENCES github_account(id),
    author_email  VARCHAR(255),
    author_name   VARCHAR(128),
    committed_at  TIMESTAMPTZ NOT NULL,
    message_head  VARCHAR(256),
    additions     INT NOT NULL DEFAULT 0,
    deletions     INT NOT NULL DEFAULT 0,
    changed_files INT NOT NULL DEFAULT 0,
    is_merge      BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE (repository_id, sha)
);

CREATE INDEX idx_commit_period      ON commit_log (committed_at);
CREATE INDEX idx_commit_repo_period ON commit_log (repository_id, committed_at);
CREATE INDEX idx_commit_author      ON commit_log (author_id, committed_at);

-- PR (통계용. 마일리지 명단에는 아직 안 쓴다)
CREATE TABLE pull_request (
    id            BIGSERIAL PRIMARY KEY,
    repository_id BIGINT NOT NULL REFERENCES repository(id),
    number        INT    NOT NULL,
    author_id     BIGINT REFERENCES github_account(id),
    title         VARCHAR(256),
    state         VARCHAR(16) NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL,
    merged_at     TIMESTAMPTZ,
    UNIQUE (repository_id, number)
);

CREATE INDEX idx_pr_author ON pull_request (author_id, created_at);

-- 동기화 이력. 문제 생기면 여기부터 본다.
CREATE TABLE sync_log (
    id            BIGSERIAL PRIMARY KEY,
    repository_id BIGINT REFERENCES repository(id),
    started_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at   TIMESTAMPTZ,
    status        VARCHAR(16) NOT NULL,      -- OK | FAILED
    new_commits   INT NOT NULL DEFAULT 0,
    error_message TEXT
);
