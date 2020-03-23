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

package cx.selenium;

@SuppressWarnings("unused")
public class GridException extends Exception {
    public GridException() {
        super();
    }
    public GridException(String s) { super(s); }
    public GridException(Throwable t) { super(t); }
    public GridException(String s, Throwable t) { super(s,t); }
}
