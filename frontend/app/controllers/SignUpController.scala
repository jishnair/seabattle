/*
 * Copyright (C) 2017  Jens Grassel & André Schütz
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package controllers

import java.util.UUID
import javax.inject.Inject

import com.mohiva.play.silhouette.api._
import com.mohiva.play.silhouette.api.repositories.AuthInfoRepository
import com.mohiva.play.silhouette.api.services.AvatarService
import com.mohiva.play.silhouette.api.util.PasswordHasherRegistry
import com.mohiva.play.silhouette.impl.providers._
import forms.SignUpForm
import models.User
import models.services.{AuthTokenService, UserService}
import org.webjars.play.WebJarsUtil
import play.api.i18n.{I18nSupport, Messages}
import play.api.libs.mailer.{Email, MailerClient}
import play.api.mvc.{
  AbstractController,
  AnyContent,
  ControllerComponents,
  Request
}
import utils.auth.DefaultEnv

import scala.concurrent.{ExecutionContext, Future}

/**
  * The `Sign Up` controller.
  *
  * @param components             The Play controller components.
  * @param silhouette             The Silhouette stack.
  * @param userService            The user service implementation.
  * @param authInfoRepository     The auth info repository implementation.
  * @param authTokenService       The auth token service implementation.
  * @param avatarService          The avatar service implementation.
  * @param passwordHasherRegistry The password hasher registry.
  * @param mailerClient           The mailer client.
  * @param webJarsUtil            The webjar util.
  * @param assets                 The Play assets finder.
  * @param ex                     The execution context.
  */
class SignUpController @Inject()(
    components: ControllerComponents,
    silhouette: Silhouette[DefaultEnv],
    userService: UserService,
    authInfoRepository: AuthInfoRepository,
    authTokenService: AuthTokenService,
    avatarService: AvatarService,
    passwordHasherRegistry: PasswordHasherRegistry,
    mailerClient: MailerClient
)(
    implicit
    webJarsUtil: WebJarsUtil,
    assets: AssetsFinder,
    ex: ExecutionContext
) extends AbstractController(components)
    with I18nSupport {

  /**
    * Views the `Sign Up` page.
    *
    * @return The result to display.
    */
  def view = silhouette.UnsecuredAction.async {
    implicit request: Request[AnyContent] =>
      Future.successful(Ok(views.html.signUp(SignUpForm.form)))
  }

  /**
    * Handles the submitted form.
    *
    * @return The result to display.
    */
  def submit = silhouette.UnsecuredAction.async {
    implicit request: Request[AnyContent] =>
      SignUpForm.form.bindFromRequest.fold(
        form => Future.successful(BadRequest(views.html.signUp(form))),
        data => {
          val result = Redirect(routes.SignUpController.view())
            .flashing("info" -> Messages("sign.up.email.sent", data.email))
          val loginInfo = LoginInfo(CredentialsProvider.ID, data.email)
          userService.retrieve(loginInfo).flatMap {
            case Some(user) =>
              val url = routes.SignInController.view().absoluteURL()
              mailerClient.send(Email(
                subject = Messages("email.already.signed.up.subject"),
                from = Messages("email.from"),
                to = Seq(data.email),
                bodyText =
                  Some(views.txt.emails.alreadySignedUp(user, url).body),
                bodyHtml =
                  Some(views.html.emails.alreadySignedUp(user, url).body)
              ))

              Future.successful(result)
            case None =>
              val authInfo = passwordHasherRegistry.current.hash(data.password)
              val user = User(
                userID = UUID.randomUUID(),
                loginInfo = loginInfo,
                firstName = Some(data.firstName),
                lastName = Some(data.lastName),
                fullName = Some(data.firstName + " " + data.lastName),
                email = Some(data.email),
                avatarURL = None,
                activated = false
              )
              for {
                avatar <- avatarService.retrieveURL(data.email)
                user <- userService.save(user.copy(avatarURL = avatar))
                authInfo <- authInfoRepository.add(loginInfo, authInfo)
                authToken <- authTokenService.create(user.userID)
              } yield {
                val url = routes.ActivateAccountController
                  .activate(authToken.id)
                  .absoluteURL()
                mailerClient.send(Email(
                  subject = Messages("email.sign.up.subject"),
                  from = Messages("email.from"),
                  to = Seq(data.email),
                  bodyText = Some(views.txt.emails.signUp(user, url).body),
                  bodyHtml = Some(views.html.emails.signUp(user, url).body)
                ))

                silhouette.env.eventBus.publish(SignUpEvent(user, request))
                result
              }
          }
        }
      )
  }
}
