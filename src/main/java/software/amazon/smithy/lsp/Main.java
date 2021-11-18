/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.smithy.lsp;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Optional;

import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;

public class Main {

  public static Optional<Exception> launch(InputStream in, OutputStream out) {
    SmithyLanguageServer server = new SmithyLanguageServer();
    Launcher<LanguageClient> launcher = LSPLauncher.createServerLauncher(server, in, out);
    LanguageClient client = launcher.getRemoteProxy();

    server.connect(client);
    try {
      launcher.startListening().get();
      return Optional.empty();
    } catch (Exception e) {
      return Optional.of(e);
    }
  }

  /**
   * @param args Arguments passed to launch server.
   */
  public static void main(String[] args) {

    Socket socket = null;
    InputStream in;
    OutputStream out;

    try {
      String port = args[0];
      // If port is set to "0", use System.in/System.out.
      if (port.equals("0")) {
        in = System.in;
        out = System.out;
      } else {
        socket = new Socket("localhost", Integer.parseInt(port));
        in = socket.getInputStream();
        out = socket.getOutputStream();
      }

      Optional<Exception> launchFailure = launch(in, out);

      if (launchFailure.isPresent())
        throw launchFailure.get();
      else
        System.out.println("Server terminated without errors");
    } catch (ArrayIndexOutOfBoundsException e) {
      System.out.println("Missing port argument");
    } catch (NumberFormatException e) {
      System.out.println("Port number must be a valid integer");
    } catch (Exception e) {
      System.out.println(e);

      e.printStackTrace();
    } finally {
      try {
        if (socket != null) {
          socket.close();
        }
      } catch (Exception e) {
        System.out.println("Failed to close the socket");
        System.out.println(e);
      }
    }
  }
}
