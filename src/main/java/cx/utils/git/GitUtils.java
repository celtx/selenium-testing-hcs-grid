/*
 * Copyright (c) Celtx Inc. <https://www.celtx.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cx.utils.git;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryBuilder;
import org.eclipse.jgit.revwalk.RevCommit;

import java.io.File;
import java.io.IOException;

public class GitUtils {
    public static String getLatestCommitId(File workingDir) {
        String latestCommitId;
        try {
            RepositoryBuilder repositoryBuilder = new RepositoryBuilder();
            repositoryBuilder.setMustExist(true);
            repositoryBuilder.setWorkTree(workingDir);
            repositoryBuilder.setGitDir(new File(workingDir, ".git"));

            Repository repository = repositoryBuilder.build();
            Git git = Git.wrap(repository);

            Iterable<RevCommit> commits = git.log().setMaxCount(1).call();
            RevCommit headCommit = commits.iterator().next();
            latestCommitId = headCommit.abbreviate(7).name();

            if (!git.status().call().isClean()) {
                latestCommitId += "-with-local-changes";
            }
        } catch (IOException | GitAPIException e) {
            throw new RuntimeException(e);
        }
        return latestCommitId;
    }
}
