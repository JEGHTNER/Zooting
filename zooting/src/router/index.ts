import { createRouter, createWebHistory } from "vue-router";
import { useAccessTokenStore } from "../stores/store";
import HomeView from "@/views/HomeView.vue";
import SignInView from "@/views/SignInView.vue";
import SignUpView from "@/views/SignUpView.vue";
import AnimalTestView from "@/views/AnimalTestView.vue";
import PersonalityTestView from "@/views/PersonalityTestView.vue";
import ProfileView from "@/views/ProfileView.vue";
import Login from "@/views/LoginView.vue";
import type {UserInfo} from "@/types/global";
import ChatTestView from "@/views/ChatTestView.vue";

function getNickname(): string | null {
  const userInfoString = localStorage.getItem("myInfo");

  if (userInfoString) {
    try {
      const userInfo: UserInfo = JSON.parse(userInfoString);
      const nickname = userInfo.nickname;

      if (!nickname) {
        return null;
      }

      return nickname;
    } catch (error) {
      console.log(error);
      return null;
    }
  } else {
    return null;
  }
}

const requireAuth = () => (to: any, from: any, next: any) => {
  const store = useAccessTokenStore();
  console.log(store.getAccessToken());
  if (store.getAccessToken()) {
    return next();
  }
  next("/signin");
};

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: "/",
      name: "home",
      component: HomeView,
    },
    {
      path: "/signin",
      name: "signin",
      component: SignInView,
    },
    {
      path: "/login",
      name: "login",
      component: Login,
    },
    {
      path: "/signup",
      name: "signup",
      component: SignUpView,
    },
    {
      path: "/animal_test",
      name: "animal_test",
      component: AnimalTestView,
    },
    {
      path: "/personality_test",
      name: "personality_test",
      component: PersonalityTestView,
    },
    {
      path: "/chat",
      name: "chat",
      component: ChatTestView,
    },
    {
      path: "/profile/:nickname?",
      name: "profile",
      component: ProfileView,
      beforeEnter: (to, from, next) => {
        if (to.params.nickname) {
          next();
        } else {
          const nickname = getNickname();
          if (nickname) {
            to.params.nickname = nickname;
            next({ name: "profile", params: { nickname: nickname } });
          } else {
            next(from);
          }
        }
      },
      children: [
        {
          path: "",
          name: "profile-check",
          component: () => import("@/components/profile/InfoMain.vue"),
        },
        {
          path: "info",
          name: "profile-info",
          component: () => import("@/components/profile/EditInfo.vue"),
        },
        {
          path: "mask/list",
          name: "profile-mask-list",
          component: () => import("@/components/profile/EditMaskList.vue"),
        },
        {
          path: "personal/info",
          name: "profile-personal-info",
          component: () => import("@/components/profile/InfoPersonal.vue"),
        },
      ],
    },
  ],
});

router.beforeEach((to, from) => {
  const store = useAccessTokenStore();
  console.log(to.name, store.isLogin);
  console.log(
    (to.name === "home" ||
      to.name === "signup" ||
      to.name === "animal_test" ||
      to.name === "personality_test") &&
      !store.isLogin
  );

  if (
    (to.name === "home" ||
      to.name === "signup" ||
      to.name === "animal_test" ||
      to.name === "personality_test") &&
    !store.isLogin
  ) {
    return { name: "signin" };
  }

  if (to.name === "signin" && store.isLogin) {
    return { name: "home" };
  }

  if (to.name === 'signup' && store.isCompletedSignUp) {
    return { name: 'home' }
  }

  // if (to.name === "animal_test" && store.userInfo?.animal) {
  //   return { name: "home" };
  // }

  if (to.name === "personality_test" && store.userInfo?.personality) {
    return { name: "home" };
  }
});

export default router;
