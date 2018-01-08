/*
 * Copyright 2015 Time Warner Cable, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.twcable.grabbit.client.services

import com.twcable.grabbit.GrabbitConfiguration

interface ClientService {

    /**
     * This API will perform Content Grab for the given configuration
     * @param configuration : the {@link GrabbitConfiguration}
     * @param clientUsername : the user that will be used by Grabbit Client
     * @return Collection of Job's Execution Ids
     */
    Collection<Long> initiateGrab(GrabbitConfiguration configuration, String clientUsername)
}
