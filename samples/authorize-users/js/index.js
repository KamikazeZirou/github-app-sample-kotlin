window.addEventListener('load', function () {
    const installations = document.getElementById('installations');
    const repositories = document.getElementById('repositories');

    function fetchRepositories(id) {
        if (!id) {
            return;
        }
        let req = new XMLHttpRequest();
        req.open('GET', '/installations/' + id + '/repositories', true);
        req.responseType = 'json';
        req.onreadystatechange = function () {
            if (req.readyState === 4 && req.status === 200) {
                console.log(req.response);

                let length = repositories.options.length;
                for (let i = length - 1; i >= 0; i--) {
                    repositories.options[i].remove();
                }

                req.response.repositories.forEach(function (e) {
                    let option = document.createElement("option");
                    option.text = e.name;
                    option.value = e.full_name;
                    repositories.add(option);
                });
            }
        };
        req.send();
    }

    fetchRepositories(installations.options[0].value)

    installations.addEventListener('change', (e) => {
        let id = e.target.value;
        fetchRepositories(id);
    });
});